// Ordinary C# consumer of two separately compiled C# libraries. Mechanism only.
using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using ClrComposition;

public static class Consumer
{
    static readonly List<string> Results = new List<string>();
    static int failures;
    static int scenarios;

    static void Require(bool condition, string message)
    {
        if (!condition) throw new Exception(message);
    }

    static void Run(string name, Action action)
    {
        scenarios++;
        try { action(); Results.Add("PASS " + name); }
        catch (Exception error) { failures++; Results.Add("FAIL " + name + " " + error); }
    }

    static void Shape<T>(Box<T> box)
    {
        var fields = typeof(Box<T>).GetFields(BindingFlags.Instance |
            BindingFlags.NonPublic | BindingFlags.Public | BindingFlags.DeclaredOnly);
        Require(fields.Length == 1 && fields[0].IsPrivate && fields[0].Name == "value" &&
            fields[0].FieldType == typeof(T), "not one authoritative !T field");
        Require(typeof(Box<T>).GetMethod("Read").ReturnType == typeof(T), "untyped natural reader");
        Require(typeof(Box<T>).GetMethod("Write").GetParameters()[0].ParameterType == typeof(T),
            "untyped natural writer");
        IBoxView view = box;
        Require(Object.ReferenceEquals(box, view), "view replaced receiver");
        var map = box.GetType().GetInterfaceMap(typeof(IBoxView));
        Require(map.TargetMethods.Length == 1 && map.TargetMethods[0].IsPrivate &&
            map.TargetMethods[0].IsFinal, "view is not an explicit implementation");
        Results.Add("STATE actual=" + box.GetType() + " field=" + fields[0].FieldType +
            " read=" + typeof(Box<T>).GetMethod("Read") + " view=" + map.TargetMethods[0]);
    }

    static void ExpectInvalidCast(Func<object> operation, string description)
    {
        try { operation(); }
        catch (InvalidCastException) { Results.Add("EXPECTED InvalidCastException " + description); return; }
        throw new Exception("Expected InvalidCastException: " + description);
    }

    static void ProducerCycle(bool nested)
    {
        var integers = new IntProducer();
        var strings = new StringProducer();
        var original = new InvalidOperationException("original bottom exception");
        var bottom = new BottomProducer(original);
        Box<IProducer> box = nested
            ? Factories.MakeNested<string>(integers) : Factories.Make<IProducer>(integers);
        var alias = box;
        Shape(box);
        Require(Object.ReferenceEquals(alias.Read(), integers), "initial producer identity");
        Require((int)alias.Read().Produce() == 73 && integers.Calls == 1, "initial body/effect");
        Writers.ReplaceProducer(box, strings);
        Require(Object.ReferenceEquals(alias.Read(), strings), "string replacement identity");
        Require((string)alias.Read().Produce() == "mixed" && strings.Calls == 1, "string body/effect");
        Writers.ReplaceProducer(box, bottom);
        Require(Object.ReferenceEquals(alias.Read(), bottom) && bottom.Calls == 0,
            "bottom identity or premature execution");
        try { alias.Read().Produce(); throw new Exception("bottom unexpectedly returned"); }
        catch (Exception error)
        {
            Require(Object.ReferenceEquals(error, original) && bottom.Calls == 1,
                "lost original throwing body/exception");
        }
        Require(Object.ReferenceEquals(box, alias) && integers.Calls == 1 && strings.Calls == 1,
            "replaced box or repeated earlier producer");
    }

    public static int Main()
    {
        Results.Add("MECHANISM ONLY runtime=" + Environment.Version + " pointerBytes=" + IntPtr.Size);
        foreach (var method in typeof(Factories).GetMethods(BindingFlags.Public | BindingFlags.Static))
            Results.Add("FACTORY " + method);

        Run("typed scalar Box<int> and separate generic writer", () => {
            var box = Factories.Make<int>(17); var alias = box;
            Shape(box);
            Require(alias.Read() == 17, "initial scalar");
            Writers.Replace<int>(box, 29);
            Require(Object.ReferenceEquals(box, alias) && alias.Read() == 29, "scalar same-state write");
        });

        Run("Make<IProducer> int/string/bottom cycle", () => ProducerCycle(false));
        Run("open MakeNested<T> int/string/bottom cycle", () => ProducerCycle(true));

        Run("projected read-only views of different invariant boxes", () => {
            var integers = new Box<int>(17); var strings = new Box<string>("first");
            Shape(integers); Shape(strings);
            var box = Factories.MakeProjected<object>(integers); var alias = box;
            Shape(box);
            Require(Object.ReferenceEquals(alias.Read(), integers) &&
                (int)alias.Read().ReadObject() == 17, "initial projected identity/value");
            Writers.Replace<int>(integers, 29);
            Require((int)alias.Read().ReadObject() == 29, "typed alias mutation not visible");
            Writers.ReplaceView(box, strings);
            Require(Object.ReferenceEquals(alias.Read(), strings), "projected replacement identity");
            Writers.Replace<string>(strings, "next");
            Require((string)alias.Read().ReadObject() == "next" && Object.ReferenceEquals(box, alias),
                "projected same-state replacement");
            Require(!((object)integers is Box<object>), "fabricated Box<object> view");
        });

        Run("broad open nullable and exact closed nullable share only view", () => {
            IBoxView open = Factories.MakeNullable<int>(17);
            Require(open.GetType() == typeof(Box<object>), "open nullable allocation is not Box<object>");
            Shape((Box<object>)open);
            var closed = new Box<int?>(41);
            Shape(closed);
            var outer = Factories.MakeProjected<int?>(open); var alias = outer;
            Shape(outer);
            Require(Object.ReferenceEquals(alias.Read(), open) && (int)alias.Read().ReadObject() == 17,
                "open nullable identity/value");
            Writers.ReplaceView(outer, closed);
            Require(Object.ReferenceEquals(alias.Read(), closed) && (int)alias.Read().ReadObject() == 41,
                "closed nullable identity/value");
            Writers.Replace<int?>(closed, null);
            Require(alias.Read().ReadObject() == null, "nullable null write through original typed alias");
            IBoxView openNull = Factories.MakeNullable<int>(null);
            Writers.ReplaceView(outer, openNull);
            Require(Object.ReferenceEquals(alias.Read(), openNull) && alias.Read().ReadObject() == null &&
                Object.ReferenceEquals(outer, alias), "open nullable null identity/state");
        });

        Run("negative same Box<object> is not Box<int?>", () => {
            IBoxView original = Factories.MakeNullable<int>(17);
            ExpectInvalidCast(() => (Box<int?>)(object)original, "Box<object> -> Box<Nullable<int>>");
            Require(original.GetType() == typeof(Box<object>) && (int)original.ReadObject() == 17,
                "capacity check changed original object/state");
        });

        Run("negative metadata-fixed Base<object> is not Base<int?>", () => {
            var original = new FixedObjectBase<int>();
            Require(original.GetType().BaseType == typeof(Base<object>), "base edge changed at substitution");
            Require((object)original is Base<object>, "missing actual base edge");
            ExpectInvalidCast(() => (Base<int?>)(object)original, "FixedObjectBase<int> -> Base<Nullable<int>>");
            Results.Add("BASE actual=" + original.GetType() + " base=" + original.GetType().BaseType);
        });

        Run("separate C# subclass typed and view routes preserve override", () => {
            var original = new ObservedStringBox("start");
            Box<string> natural = original; IBoxView view = original;
            Shape(natural);
            Require(Object.ReferenceEquals(natural, view) && natural.Read() == "start!" && original.Reads == 1,
                "typed route lost override");
            Require((string)view.ReadObject() == "start!" && original.Reads == 2,
                "view route bypassed override");
            Writers.Replace<string>(natural, "changed");
            Require((string)view.ReadObject() == "changed!" && original.Reads == 3,
                "view lost same inherited field or repeated body");
        });

        Results.Add("SUMMARY scenarios=" + scenarios + " failures=" + failures);
        File.WriteAllLines(Path.Combine(Path.GetDirectoryName(typeof(Consumer).Assembly.Location), "results.txt"), Results);
        foreach (var result in Results) Console.WriteLine(result);
        return failures == 0 ? 0 : 1;
    }
}
