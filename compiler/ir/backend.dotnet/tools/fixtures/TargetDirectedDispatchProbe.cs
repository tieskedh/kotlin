/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

using System;
using System.Reflection;
using System.Runtime.ExceptionServices;
using SelectedViewStorageLibrary;

// Standalone CLR observations plus one explicitly bounded experimental policy.
// Not Kotlin subtyping, compiler integration, a storage ABI or a dispatch benchmark.
public static class TargetDirectedDispatchProbe
{
    private class Animal { }
    private sealed class Cat : Animal { }
    private sealed class Dog : Animal { }

    private sealed class CatFirst : ISource<Cat>, ISource<Dog>
    {
        public readonly Cat Cat = new Cat();
        public readonly Dog Dog = new Dog();
        public int CatCalls;
        public int DogCalls;
        Cat ISource<Cat>.Read() { CatCalls++; return Cat; }
        Dog ISource<Dog>.Read() { DogCalls++; return Dog; }
    }

    private sealed class DogFirst : ISource<Dog>, ISource<Cat>
    {
        public readonly Cat Cat = new Cat();
        public readonly Dog Dog = new Dog();
        public int CatCalls;
        public int DogCalls;
        Cat ISource<Cat>.Read() { CatCalls++; return Cat; }
        Dog ISource<Dog>.Read() { DogCalls++; return Dog; }
    }

    private class Base : ISource<int>
    {
        public int BaseCalls;
        public virtual int Read() { BaseCalls++; return 31; }
    }

    private sealed class Child : Base, ISource<int>
    {
        public int InterfaceCalls;
        public bool Fail;
        public readonly Exception Failure = new InvalidOperationException("child interface failure");
        int ISource<int>.Read()
        {
            InterfaceCalls++;
            if (Fail) throw Failure;
            return 47;
        }
    }

    private sealed class DualValue : ISource<int>, ISource<long>
    {
        public int Calls;
        int ISource<int>.Read() { Calls++; return 7; }
        long ISource<long>.Read() { Calls++; return 13L; }
    }

    private sealed class MixedValueReference : ISource<int>, ISource<string>
    {
        int ISource<int>.Read() { return 19; }
        string ISource<string>.Read() { return "mixed reference"; }
    }

    private static void Require(bool condition, string message)
    {
        if (!condition) throw new Exception(message);
    }

    private static bool DeclaresInterface(object receiver, Type target)
    {
        foreach (Type candidate in receiver.GetType().GetInterfaces())
            if (candidate == target) return true;
        return false;
    }

    private static object InvokeSource(object receiver, Type physicalView)
    {
        // A valid variant view need not have its own InterfaceImpl row.
        Require(physicalView.IsInstanceOfType(receiver), "Invocation fabricated a CLR interface view");
        MethodInfo declaration = typeof(ISource<>).GetMethod("Read");
        MethodInfo method = (MethodInfo)MethodBase.GetMethodFromHandle(declaration.MethodHandle, physicalView.TypeHandle);
        Require(method.DeclaringType == physicalView, "MethodDef bound to the wrong physical construction");
        try { return method.Invoke(receiver, null); }
        catch (TargetInvocationException failure)
        {
            // Reflection wrapping is not the operation's user exception.
            ExceptionDispatchInfo.Capture(failure.InnerException).Throw();
            throw;
        }
    }

    private static void CheckNativeVariantTargets()
    {
        var first = new CatFirst();
        ISource<Cat> firstSource = first;
        ISource<Animal> firstTarget = Boundary.Forward<ISource<Animal>>(firstSource);
        Require(!DeclaresInterface(first, typeof(ISource<Animal>)), "Probe accidentally declares its target row");
        Require(typeof(ISource<Animal>).IsInstanceOfType(first), "Native reference variance is unavailable");
        Animal directFirst = firstTarget.Read();
        Require(first.CatCalls + first.DogCalls == 1, "Native invocation called more than one implementation");
        object reflectedFirst = InvokeSource(first, typeof(ISource<Animal>));
        Require(Object.ReferenceEquals(directFirst, reflectedFirst) &&
            first.CatCalls + first.DogCalls == 2, "Closed MethodDef did not reproduce native target dispatch");
        Require(Object.ReferenceEquals(firstSource, firstTarget), "Native variance changed the receiver");
        object opaque = Boundary.Echo(firstTarget);
        var stored = new Box<object>(opaque);
        Require(Object.ReferenceEquals(stored.Value, first) &&
            Object.ReferenceEquals(InvokeSource(stored.Value, typeof(ISource<Animal>)), directFirst),
            "Object storage changed target-directed native dispatch");

        // Deliberately reverse physical declarations. Do not assert a portable winner:
        // the CLR, not this probe's enumeration order, owns variant dispatch.
        var second = new DogFirst();
        ISource<Animal> secondTarget = Boundary.Forward<ISource<Animal>>((ISource<Dog>)second);
        Require(!DeclaresInterface(second, typeof(ISource<Animal>)), "Reversed probe declares the target row");
        Animal directSecond = secondTarget.Read();
        object reflectedSecond = InvokeSource(second, typeof(ISource<Animal>));
        Require(Object.ReferenceEquals(directSecond, reflectedSecond) &&
            second.CatCalls + second.DogCalls == 2, "Reversed native target dispatch changed");
        Console.WriteLine("PASS: native target without exact row; multiple variant sources; CLR/direct agreement; winners=" +
            directFirst.GetType().Name + "/" + directSecond.GetType().Name);
    }

    private static void CheckExactTargetAndUniqueValueSource()
    {
        ISource<string> source = new DualReference();
        ISource<object> target = Boundary.Forward<ISource<object>>(source);
        Require(DeclaresInterface(target, typeof(ISource<object>)), "Exact target row is missing");
        Require(source.Read() == "native string" && target.Read().Equals("native object"), "Exact target did not change dispatch");
        object opaque = Boundary.Echo(source);
        Require(Object.ReferenceEquals(opaque, target) &&
            InvokeSource(opaque, typeof(ISource<object>)).Equals("native object"), "Raw object retained historical native selection");

        var valueSource = new IntOnly();
        Require(!typeof(ISource<object>).IsInstanceOfType(valueSource), "Value variance fabricated a native target");
        Require(InvokeSource(Boundary.Echo(valueSource), typeof(ISource<int>)).Equals(11),
            "Real value-source MethodDef could not produce a boxed result");
        Console.WriteLine("PASS: exact native target wins; unique value source uses its real MethodDef, not ISource<object>");
    }

    private static void CheckReimplementationAndExceptions()
    {
        var child = new Child();
        Require(((Base)child).Read() == 31 && child.BaseCalls == 1, "Class virtual call changed");
        Require(((ISource<int>)child).Read() == 47 && child.InterfaceCalls == 1, "Explicit interface reimplementation was bypassed");
        Require(InvokeSource(child, typeof(ISource<int>)).Equals(47) &&
            child.InterfaceCalls == 2 && child.BaseCalls == 1, "MethodDef invocation bypassed the runtime interface map");
        child.Fail = true;
        Exception direct = null;
        Exception reflected = null;
        try { ((ISource<int>)child).Read(); }
        catch (Exception failure) { direct = failure; }
        try { InvokeSource(child, typeof(ISource<int>)); }
        catch (Exception failure) { reflected = failure; }
        Require(Object.ReferenceEquals(direct, child.Failure) && Object.ReferenceEquals(reflected, child.Failure),
            "Invocation changed exception identity or leaked reflection wrapping");
        Require(child.InterfaceCalls == 4 && child.BaseCalls == 1, "Invocation duplicated effects or probed a different implementation");
        Console.WriteLine("PASS: child interface reimplementation; one effect per call; original exception identity");
    }

    private enum ProducerOutcome { ExactTarget, NativeVariantTarget, UniqueSource, Ambiguous, Missing }

    // A hypothesis ONLY for the one-member ISource<out T> family and an object
    // result operation. It does not implement Kotlin compatibility, input policy,
    // star semantics, arbitrary MethodDefs or a history-preservation guarantee.
    private static ProducerOutcome ClassifyObjectProducer(object receiver, out Type invocationView)
    {
        invocationView = null;
        Type target = typeof(ISource<object>);
        if (DeclaresInterface(receiver, target))
        {
            invocationView = target;
            return ProducerOutcome.ExactTarget;
        }
        if (target.IsInstanceOfType(receiver))
        {
            invocationView = target;
            return ProducerOutcome.NativeVariantTarget;
        }
        Type unique = null;
        int count = 0;
        foreach (Type candidate in receiver.GetType().GetInterfaces())
        {
            if (!candidate.IsGenericType || candidate.GetGenericTypeDefinition() != typeof(ISource<>)) continue;
            unique = candidate;
            count++;
        }
        if (count > 1) return ProducerOutcome.Ambiguous;
        if (count == 0) return ProducerOutcome.Missing;
        invocationView = unique;
        return ProducerOutcome.UniqueSource;
    }

    private static void CheckProducerOnlyHypothesis()
    {
        Type view;
        var dual = new Dual();
        Require(ClassifyObjectProducer(dual, out view) == ProducerOutcome.ExactTarget &&
            view == typeof(ISource<object>) && InvokeSource(dual, view).Equals("object"), "Exact-target hypothesis failed");
        var only = new IntOnly();
        Require(ClassifyObjectProducer(only, out view) == ProducerOutcome.UniqueSource &&
            view == typeof(ISource<int>) && InvokeSource(only, view).Equals(11), "Unique-source hypothesis fabricated a target");
        var reference = new StringOnly();
        Require(ClassifyObjectProducer(reference, out view) == ProducerOutcome.NativeVariantTarget &&
            view == typeof(ISource<object>), "Native variance was confused with an exact row");

        var mixed = new MixedValueReference();
        Require(DeclaresInterface(mixed, typeof(ISource<int>)) && DeclaresInterface(mixed, typeof(ISource<string>)) &&
            !DeclaresInterface(mixed, typeof(ISource<object>)), "Mixed candidate graph changed");
        Require(ClassifyObjectProducer(mixed, out view) == ProducerOutcome.NativeVariantTarget &&
            view == typeof(ISource<object>) && InvokeSource(mixed, view).Equals("mixed reference") &&
            ((ISource<object>)(ISource<string>)mixed).Read().Equals("mixed reference"),
            "Supported native target priority was confused with exact-row priority or semantic uniqueness");
        // This native-compatible target ignores the int route. Choosing it for a
        // Kotlin-owned widened family is an observable interop POLICY, not a proof
        // that the two physical constructions describe one Kotlin implementation.

        var ambiguous = new DualValue();
        Require(DeclaresInterface(ambiguous, typeof(ISource<int>)) && DeclaresInterface(ambiguous, typeof(ISource<long>)),
            "Ambiguous receiver does not implement the producer family");
        Require(ClassifyObjectProducer(ambiguous, out view) == ProducerOutcome.Ambiguous && view == null && ambiguous.Calls == 0,
            "Classification guessed a member or invoked candidates");
        Require(ClassifyObjectProducer(new object(), out view) == ProducerOutcome.Missing && view == null,
            "Missing family was confused with dispatch ambiguity");
        Require(ClassifyObjectProducer(Boundary.Echo(dual), out view) == ProducerOutcome.ExactTarget &&
            InvokeSource(dual, view).Equals("object"), "Object roundtrip required historical selection");
        Console.WriteLine("PASS: bounded producer-only hypothesis; exact/variant/unique/ambiguous/missing are distinct; no Kotlin policy accepted");
    }

    public static void Run()
    {
        CheckNativeVariantTargets();
        CheckExactTargetAndUniqueValueSource();
        CheckReimplementationAndExceptions();
        CheckProducerOnlyHypothesis();
    }
}
