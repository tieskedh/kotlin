/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

using System;
using System.Reflection;

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

    public static SelectedView From<T>(ISource<T> value)
    {
        return Checked(value, typeof(ISource<T>));
    }

    public static SelectedView Checked(object value, Type view)
    {
        if (value == null) throw new NullReferenceException();
        if (!view.IsConstructedGenericType ||
            view.GetGenericTypeDefinition() != typeof(ISource<>) ||
            !view.IsInstanceOfType(value))
            throw new InvalidCastException("Selection cannot establish a physical view");
        return new SelectedView(value, view);
    }

    public object Read()
    {
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
        Console.WriteLine("PASS: stored selections, same receiver, typed independent fields, joins, rejected forged views; runtime=" + Environment.Version);
        return 0;
    }
}
