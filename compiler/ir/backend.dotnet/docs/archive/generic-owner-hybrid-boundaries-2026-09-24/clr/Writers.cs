// Compiled separately against Producer.dll, with no producer source included.
namespace ClrComposition
{
    public static class Writers
    {
        public static void Replace<T>(Box<T> box, T replacement)
        {
            box.Write(replacement);
        }

        public static void ReplaceProducer(Box<IProducer> box, IProducer replacement)
        {
            box.Write(replacement);
        }

        public static void ReplaceView(Box<IBoxView> box, IBoxView replacement)
        {
            box.Write(replacement);
        }
    }

    // One inherited authoritative value field; this counter is an observable effect,
    // not a second copy of the stored value. Both routes must see this override.
    public sealed class ObservedStringBox : Box<string>
    {
        private int reads;
        public ObservedStringBox(string initial) : base(initial) { }
        public int Reads { get { return reads; } }
        public override string Read() { reads++; return base.Read() + "!"; }
    }
}
