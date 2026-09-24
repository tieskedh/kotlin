// Standalone CLR mechanism hypothesis. This is not generated Kotlin output.
using System;

namespace ClrComposition
{
    // Explicit proposed C# contract: one erased producer, with object results.
    public interface IProducer { object Produce(); }

    // Explicit proposed READ-ONLY view. This does not authorize arbitrary writes
    // through a projected view, and is not a hidden Kotlin compiler capability.
    public interface IBoxView { object ReadObject(); }

    public class Box<T> : IBoxView
    {
        private T value;
        public Box(T initial) { value = initial; }
        public virtual T Read() { return value; }
        public virtual void Write(T replacement) { value = replacement; }
        object IBoxView.ReadObject() { return Read(); }
    }

    public sealed class IntProducer : IProducer
    {
        private int calls;
        public int Calls { get { return calls; } }
        public object Produce() { calls++; return 73; }
    }

    public sealed class StringProducer : IProducer
    {
        private int calls;
        public int Calls { get { return calls; } }
        public object Produce() { calls++; return "mixed"; }
    }

    public sealed class BottomProducer : IProducer
    {
        private readonly Exception original;
        private int calls;
        public BottomProducer(Exception original) { this.original = original; }
        public int Calls { get { return calls; } }
        public object Produce() { calls++; throw original; }
    }

    public class Base<T> { }

    // The base edge is fixed in metadata, not recomputed as Nullable<T> at use.
    public sealed class FixedObjectBase<T> : Base<object> { }

    public static class Factories
    {
        public static Box<T> Make<T>(T value) { return new Box<T>(value); }

        // T models a logical parameter erased from this proposed inner contract.
        // CLR does not check a relation between T and IProducer/IBoxView/object.
        public static Box<IProducer> MakeNested<T>(IProducer value)
        {
            return new Box<IProducer>(value);
        }

        public static Box<IBoxView> MakeProjected<T>(IBoxView value)
        {
            return new Box<IBoxView>(value);
        }

        // Broad explicit API: this does NOT promise Box<T?> or Box<Nullable<T>>.
        public static IBoxView MakeNullable<T>(object value)
        {
            return new Box<object>(value);
        }
    }
}
