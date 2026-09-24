// Distinct expected compile failure, never included in the three positive assemblies.
public static class UnrestrictedNullable
{
    // CS0453: unrestricted T cannot construct System.Nullable<T>.
    public static System.Nullable<T> Make<T>(T value)
    {
        return new System.Nullable<T>(value);
    }
}
