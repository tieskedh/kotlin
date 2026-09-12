/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

using System.Runtime.CompilerServices;

// Compiled BEFORE the consumer, without a reference to SelectedView or ISource<T>.
// This is ordinary CLR library code, not a proposed Kotlin ABI.
namespace SelectedViewStorageLibrary
{
    public sealed class Box<T>
    {
        private T value;

        public Box(T value) { this.value = value; }
        public T Value { get { return value; } set { this.value = value; } }
    }

    public static class Boundary
    {
        [MethodImpl(MethodImplOptions.NoInlining)]
        public static T Forward<T>(T value) { return value; }

        [MethodImpl(MethodImplOptions.NoInlining)]
        public static object Echo(object value) { return value; }
    }

    // Only an explicit lock-based mechanism proof. Not an implementation of
    // Kotlin volatile/atomic fields or permission to strengthen arbitrary stores.
    public sealed class LockedSlot<T>
    {
        private readonly object gate = new object();
        private T value;

        public LockedSlot(T value) { this.value = value; }
        public T Load() { lock (gate) { return value; } }
        public void Store(T next) { lock (gate) { value = next; } }
    }
}
