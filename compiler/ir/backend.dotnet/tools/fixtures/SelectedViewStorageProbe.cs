/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

using System;
using System.Reflection;
using System.Threading;
using SelectedViewStorageLibrary;

public interface ISource<out T> { T Read(); }

public sealed class Dual : ISource<object>, ISource<int>
{
    object ISource<object>.Read() { return "object"; }
    int ISource<int>.Read() { return 7; }
}

public sealed class IntOnly : ISource<int>
{
    public int Read() { return 11; }
}

public sealed class StringOnly : ISource<string>
{
    public string Read() { return "reference variance"; }
}

public sealed class DualReference : ISource<object>, ISource<string>
{
    object ISource<object>.Read() { return "native object"; }
    string ISource<string>.Read() { return "native string"; }
}

// An experimental storage layout, NOT a proxy implementation of ISource<T>.
// The only receiver is Target. Selection does not establish an interface view.
public struct SelectedView
{
    private readonly object target;
    private readonly Type selection;

    private SelectedView(object target, Type selection)
    {
        this.target = target;
        this.selection = selection;
    }

    public object Target { get { return target; } }
    public bool IsNull { get { return target == null; } }

    public static SelectedView From<T>(ISource<T> value)
    {
        return Checked(value, typeof(ISource<T>));
    }

    public static SelectedView Checked(object value, Type view)
    {
        if (value == null) throw new NullReferenceException();
        if (view == null || !view.IsConstructedGenericType ||
            view.GetGenericTypeDefinition() != typeof(ISource<>) ||
            !view.IsInstanceOfType(value))
            throw new InvalidCastException("Selection cannot establish a physical view");
        return new SelectedView(value, view);
    }

    public object Read()
    {
        // The all-zero value is the probe's null representation, not a forged view.
        if (IsNull) throw new NullReferenceException();
        MethodInfo declaration = typeof(ISource<>).GetMethod("Read");
        MethodBase bound = MethodBase.GetMethodFromHandle(declaration.MethodHandle, selection.TypeHandle);
        return bound.Invoke(target, null);
    }
}

public sealed class Store<T>
{
    private readonly T value;
    private SelectedView source;

    public Store(T value, SelectedView source)
    {
        this.value = value;
        this.source = source;
    }

    public T Value { get { return value; } }
    public object Identity { get { return source.Target; } }
    public object Read() { return source.Read(); }
    public void Replace(SelectedView next) { source = next; }
}

public static class SelectedViewStorageProbe
{
    private static void Require(bool value, string message)
    {
        if (!value) throw new Exception(message);
    }

    private static void CheckSeparateStorage(Dual dual, SelectedView objectView, SelectedView intView)
    {
        Require(typeof(Box<>).Assembly != typeof(SelectedView).Assembly, "Container was not compiled separately");
        foreach (AssemblyName reference in typeof(Box<>).Assembly.GetReferencedAssemblies())
            Require(reference.Name != typeof(SelectedView).Assembly.GetName().Name, "Container knows the consumer");

        const BindingFlags Fields = BindingFlags.Instance | BindingFlags.NonPublic | BindingFlags.DeclaredOnly;
        Require(typeof(Box<>).GetField("value", Fields).FieldType.IsGenericParameter, "Generic field erased");
        Require(typeof(Box<int>).GetField("value", Fields).FieldType == typeof(int) &&
            typeof(Box<string>).GetField("value", Fields).FieldType == typeof(string) &&
            typeof(Box<SelectedView>).GetField("value", Fields).FieldType == typeof(SelectedView), "Incorrect physical substitution");
        Require(new Box<int>(17).Value == 17 && new Box<string>("exact").Value == "exact", "Ordinary typed state changed");

        var selected = new Box<SelectedView>(objectView);
        var nested = new Box<Box<SelectedView>>(selected);
        for (int i = 0; i < 20; i++)
        {
            selected.Value = Boundary.Forward(i % 2 == 0 ? intView : objectView);
            SelectedView restored = Boundary.Forward(nested.Value.Value);
            Require(Object.ReferenceEquals(restored.Target, dual), "Separate storage changed receiver");
            Require(restored.Read().Equals(i % 2 == 0 ? (object)7 : "object"), "Separate storage lost selection");
        }

        var missing = new Box<SelectedView>(default(SelectedView));
        Require(missing.Value.IsNull && missing.Value.Target == null, "Null storage changed");
        try
        {
            missing.Value.Read();
            throw new Exception("Null receiver was invoked");
        }
        catch (NullReferenceException) { }

        // This is not Box<ISource<object>>: a value-only construction does not
        // become assignable to that natural slot just because a pair can carry it.
        var only = new IntOnly();
        selected.Value = SelectedView.From<int>(only);
        Require(selected.Value.Read().Equals(11), "Value construction lost in generic state");
        try
        {
            var impossible = new Box<ISource<object>>((ISource<object>)(object)only);
            throw new Exception("Fabricated nested construction: " + impossible.Value);
        }
        catch (InvalidCastException) { }
        Console.WriteLine("PASS: separate generic DLL, forwarding, nested state, null, natural-slot negative");
    }

    private static void CheckObjectBoundary(Dual dual, SelectedView objectView, SelectedView intView)
    {
        object first = Boundary.Echo(objectView.Target);
        object second = Boundary.Echo(intView.Target);
        Require(Object.ReferenceEquals(first, dual) && Object.ReferenceEquals(first, second), "Object identity changed");
        Require(!objectView.Read().Equals(intView.Read()), "Selections did not differ before export");

        // A caller-supplied target establishes a NEW checked view; it cannot
        // reconstruct the distinct views which were discarded at the boundary.
        SelectedView reselectedFirst = SelectedView.Checked(first, typeof(ISource<object>));
        SelectedView reselectedSecond = SelectedView.Checked(second, typeof(ISource<object>));
        Require(reselectedFirst.Read().Equals("object") && reselectedSecond.Read().Equals("object"), "New view did not use its target");
        Require(!reselectedSecond.Read().Equals(intView.Read()), "Test failed to expose selection loss");

        // Boxing the carrier transports selection, but it is NOT exporting the
        // original receiver as Any/object. Both outcomes must remain explicit.
        object boxedPair = Boundary.Echo(intView);
        Require(boxedPair is SelectedView && !Object.ReferenceEquals(boxedPair, dual), "Boxing negative did not change identity");
        Require(((SelectedView)boxedPair).Read().Equals(7), "Boxed carrier unexpectedly lost selection");

        // Even valid CLR reference covariance must respect the target slot.
        // Retaining source selection would change this native C# operation.
        ISource<string> referenceSource = new DualReference();
        ISource<object> referenceTarget = Boundary.Forward<ISource<object>>(referenceSource);
        Require(Object.ReferenceEquals(referenceSource, referenceTarget), "Native conversion changed identity");
        Require(referenceSource.Read() == "native string" && referenceTarget.Read().Equals("native object"), "Wrong native target slot");
        Require(SelectedView.From<string>(referenceSource).Read().Equals("native string") &&
            SelectedView.From<object>(referenceSource).Read().Equals("native object"), "Entry did not establish its physical view");
        Console.WriteLine("PASS: object boundary preserves receiver but loses selection; pair boxing changes identity");
    }

    private static void CheckCoherentStorage()
    {
        var firstReceiver = new IntOnly();
        var secondReceiver = new StringOnly();
        SelectedView first = SelectedView.From<int>(firstReceiver);
        SelectedView second = SelectedView.From<string>(secondReceiver);
        var slot = new LockedSlot<SelectedView>(first);
        const int Iterations = 20000;
        var failures = new Exception[4];
        var completed = new int[4];
        var workers = new Thread[4];
        using (var start = new ManualResetEvent(false))
        {
            for (int i = 0; i < workers.Length; i++)
            {
                int worker = i;
                workers[i] = new Thread(delegate()
                {
                    try
                    {
                        start.WaitOne();
                        for (int iteration = 0; iteration < Iterations; iteration++)
                        {
                            if (worker < 2)
                                slot.Store((iteration + worker) % 2 == 0 ? first : second);
                            else
                            {
                                // One snapshot, not separate reads of receiver and selection.
                                // User dispatch happens AFTER releasing the lock.
                                SelectedView snapshot = slot.Load();
                                bool isFirst = Object.ReferenceEquals(snapshot.Target, firstReceiver);
                                Require(isFirst || Object.ReferenceEquals(snapshot.Target, secondReceiver), "Unknown receiver");
                                Require(snapshot.Read().Equals(isFirst ? (object)11 : "reference variance"), "Incoherent pair");
                            }
                            completed[worker]++;
                        }
                    }
                    catch (Exception failure) { failures[worker] = failure; }
                });
                workers[i].IsBackground = true;
                workers[i].Start();
            }
            start.Set();
            foreach (Thread worker in workers) Require(worker.Join(30000), "Coherent-storage worker timed out");
        }
        for (int i = 0; i < workers.Length; i++)
        {
            if (failures[i] != null) throw new Exception("Coherent-storage worker failed", failures[i]);
            Require(completed[i] == Iterations, "Missing concurrent iterations");
        }
        Console.WriteLine("PASS: locked whole-pair storage; 40000 writes and 40000 snapshot reads; not volatile/lock-free proof");
    }

    public static int Main()
    {
        var dual = new Dual();
        var objectView = SelectedView.From<object>(dual);
        var intView = SelectedView.From<int>(dual);
        var first = new Store<int>(41, objectView);
        var second = new Store<string>("typed", intView);
        Require(first.Read().Equals("object") && second.Read().Equals(7), "Selection lost in state");
        Require(Object.ReferenceEquals(first.Identity, second.Identity) &&
            Object.ReferenceEquals(first.Identity, dual), "Receiver identity changed");
        Require(first.Value == 41 && second.Value == "typed", "Independent typed state changed");
        const BindingFlags Fields = BindingFlags.Instance | BindingFlags.NonPublic | BindingFlags.DeclaredOnly;
        Require(typeof(Store<int>).GetField("value", Fields).FieldType == typeof(int) &&
            typeof(Store<string>).GetField("value", Fields).FieldType == typeof(string), "Typed fields erased");
        Require(typeof(Store<int>).GetFields(Fields).Length == 2 &&
            typeof(Store<int>).GetField("source", Fields).FieldType == typeof(SelectedView), "Unexpected storage");
        Require(typeof(SelectedView).IsValueType, "View became a receiver proxy");
        for (int i = 0; i < 20; i++)
        {
            SelectedView joined = (i % 2 == 0) ? intView : objectView;
            first.Replace(joined);
            Require(first.Read().Equals(i % 2 == 0 ? (object)7 : "object"), "Join or mutable store lost selection");
            Require(Object.ReferenceEquals(first.Identity, dual), "Mutable store changed receiver identity");
        }
        Require(SelectedView.From<int>(new IntOnly()).Read().Equals(11), "Single construction failed");
        var text = new StringOnly();
        var referenceView = SelectedView.From<object>(text);
        Require(referenceView.Read().Equals("reference variance") &&
            Object.ReferenceEquals(referenceView.Target, text), "Native reference variance failed");
        try
        {
            SelectedView.Checked(new IntOnly(), typeof(ISource<object>));
            throw new Exception("Fabricated value-type variance was accepted");
        }
        catch (InvalidCastException) { }
        try
        {
            SelectedView.Checked(dual, typeof(ISource<long>));
            throw new Exception("Forged selection was accepted");
        }
        catch (InvalidCastException) { }
        CheckSeparateStorage(dual, objectView, intView);
        CheckObjectBoundary(dual, objectView, intView);
        CheckCoherentStorage();
        TargetDirectedDispatchProbe.Run();
        Console.WriteLine("PASS: stored selections, same receiver, typed independent fields, joins, rejected forged views; runtime=" + Environment.Version);
        return 0;
    }
}
