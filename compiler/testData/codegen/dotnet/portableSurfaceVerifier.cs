// Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
// Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.Loader;

internal static class Program
{
    private sealed class PairLoadContext : AssemblyLoadContext
    {
        private readonly string directory;

        internal PairLoadContext(string directory)
            : base(isCollectible: true)
        {
            this.directory = directory;
        }

        protected override Assembly Load(AssemblyName assemblyName)
        {
            string candidate = Path.Combine(directory, assemblyName.Name + ".dll");
            return File.Exists(candidate) ? LoadFromAssemblyPath(candidate) : null;
        }
    }

    private sealed class SurfaceItem
    {
        internal SurfaceItem(string key, int access, bool isMethod, bool isAbstract, bool isFinal)
        {
            Key = key;
            Access = access;
            IsMethod = isMethod;
            IsAbstract = isAbstract;
            IsFinal = isFinal;
        }

        internal string Key { get; }
        internal int Access { get; }
        internal bool IsMethod { get; }
        internal bool IsAbstract { get; }
        internal bool IsFinal { get; }
    }

    private static int Main(string[] args)
    {
        if (args.Length != 4)
        {
            Console.Error.WriteLine(
                "usage: verifier <portable-runtime> <platform-runtime> <portable-stdlib> <platform-stdlib>");
            return 2;
        }

        PairLoadContext portableContext = new PairLoadContext(Path.GetDirectoryName(Path.GetFullPath(args[0])));
        PairLoadContext platformContext = new PairLoadContext(Path.GetDirectoryName(Path.GetFullPath(args[1])));
        try
        {
            Dictionary<string, SurfaceItem> portable = Capture(
                portableContext.LoadFromAssemblyPath(Path.GetFullPath(args[0])),
                portableContext.LoadFromAssemblyPath(Path.GetFullPath(args[2])));
            Dictionary<string, SurfaceItem> platform = Capture(
                platformContext.LoadFromAssemblyPath(Path.GetFullPath(args[1])),
                platformContext.LoadFromAssemblyPath(Path.GetFullPath(args[3])));
            if (portable.Count == 0)
                throw new InvalidOperationException("The portable CLR surface is empty.");

            List<string> differences = Compare(portable, platform);
            if (differences.Count != 0)
            {
                foreach (string difference in differences)
                    Console.Error.WriteLine(difference);
                return 1;
            }

            Console.WriteLine("OK " + portable.Count.ToString(CultureInfo.InvariantCulture));
            return 0;
        }
        catch (Exception failure)
        {
            Console.Error.WriteLine(failure);
            return 3;
        }
        finally
        {
            portableContext.Unload();
            platformContext.Unload();
        }
    }

    private static Dictionary<string, SurfaceItem> Capture(params Assembly[] assemblies)
    {
        var result = new Dictionary<string, SurfaceItem>(StringComparer.Ordinal);
        foreach (Assembly assembly in assemblies.OrderBy(value => value.GetName().Name, StringComparer.Ordinal))
        {
            AddAttributes(
                result,
                "ASSEMBLY:" + assembly.GetName().Name,
                assembly.CustomAttributes.Where(attribute =>
                    attribute.AttributeType.FullName != "System.Runtime.Versioning.TargetFrameworkAttribute"),
                access: 3);
            foreach (Type type in assembly.GetTypes().OrderBy(TypeIdentity, StringComparer.Ordinal))
            {
                int typeAccess = TypeAccess(type);
                if (typeAccess == 0)
                    continue;

                string owner = TypeIdentity(type);
                TypeAttributes typeShape = type.Attributes & ~TypeAttributes.VisibilityMask;
                string typeKey =
                    "TYPE|" + owner + "|" + ((int)typeShape).ToString(CultureInfo.InvariantCulture);
                Add(result, new SurfaceItem(
                    typeKey,
                    typeAccess,
                    isMethod: false,
                    isAbstract: false,
                    isFinal: false));
                AddAttributes(result, typeKey, type.CustomAttributes, typeAccess);

                if (type.BaseType != null)
                    AddFact(result, "BASE|" + owner + "|" + TypeIdentity(type.BaseType), typeAccess);
                foreach (Type implemented in type.GetInterfaces().OrderBy(TypeIdentity, StringComparer.Ordinal))
                    AddFact(result, "INTERFACE|" + owner + "|" + TypeIdentity(implemented), typeAccess);
                AddGenericParameters(result, "TYPE_PARAMETER|" + owner, type.GetGenericArguments(), typeAccess);

                const BindingFlags declaredMembers = BindingFlags.Public | BindingFlags.NonPublic |
                    BindingFlags.Instance | BindingFlags.Static | BindingFlags.DeclaredOnly;
                foreach (ConstructorInfo constructor in type.GetConstructors(declaredMembers))
                    AddMethod(result, owner, constructor);
                foreach (MethodInfo method in type.GetMethods(declaredMembers))
                    AddMethod(result, owner, method);
                foreach (FieldInfo field in type.GetFields(declaredMembers))
                    AddField(result, owner, field);
                foreach (PropertyInfo property in type.GetProperties(declaredMembers))
                    AddProperty(result, owner, property);
                foreach (EventInfo eventInfo in type.GetEvents(declaredMembers))
                    AddEvent(result, owner, eventInfo);
            }
        }
        return result;
    }

    private static List<string> Compare(
        Dictionary<string, SurfaceItem> portable,
        Dictionary<string, SurfaceItem> platform)
    {
        var differences = new List<string>();
        foreach (SurfaceItem required in portable.Values.OrderBy(item => item.Key, StringComparer.Ordinal))
        {
            if (!platform.TryGetValue(required.Key, out SurfaceItem actual))
            {
                differences.Add("MISSING " + required.Key);
                continue;
            }
            if (actual.Access < required.Access)
                differences.Add("NARROWED " + required.Key);
            if (required.IsMethod && !required.IsAbstract && actual.IsAbstract)
                differences.Add("ABSTRACTED " + required.Key);
            if (required.IsMethod && !required.IsFinal && actual.IsFinal)
                differences.Add("SEALED " + required.Key);
        }
        return differences;
    }

    private static void AddMethod(Dictionary<string, SurfaceItem> surface, string owner, MethodBase method)
    {
        int access = MethodAccess(method);
        if (access == 0)
            return;

        MethodAttributes shape = method.Attributes & (
            MethodAttributes.Static |
            MethodAttributes.Virtual |
            MethodAttributes.NewSlot |
            MethodAttributes.HideBySig |
            MethodAttributes.SpecialName |
            MethodAttributes.RTSpecialName |
            MethodAttributes.PinvokeImpl);
        string genericShape = GenericParameterShape(method.IsGenericMethod
            ? method.GetGenericArguments()
            : Type.EmptyTypes);
        string parameters = string.Join(",", method.GetParameters().Select(ParameterShape));
        string result = method is MethodInfo methodInfo ? ParameterShape(methodInfo.ReturnParameter) : "void";
        string key = "METHOD|" + owner + "|" + method.Name + "|" +
            ((int)shape).ToString(CultureInfo.InvariantCulture) + "|" +
            ((int)method.CallingConvention).ToString(CultureInfo.InvariantCulture) + "|" +
            genericShape + "|(" + parameters + ")->" + result;
        Add(surface, new SurfaceItem(key, access, isMethod: true, method.IsAbstract, method.IsFinal));
        AddAttributes(surface, key, method.CustomAttributes, access);
        ParameterInfo[] methodParameters = method.GetParameters();
        for (int index = 0; index < methodParameters.Length; index++)
            AddAttributes(surface, key + "|PARAMETER:" + index, methodParameters[index].CustomAttributes, access);
        if (method is MethodInfo returnMethod)
            AddAttributes(surface, key + "|RETURN", returnMethod.ReturnParameter.CustomAttributes, access);
        if (method.IsGenericMethod)
        {
            Type[] genericParameters = method.GetGenericArguments();
            for (int index = 0; index < genericParameters.Length; index++)
                AddAttributes(surface, key + "|TYPE_PARAMETER:" + index, genericParameters[index].CustomAttributes, access);
        }
    }

    private static void AddField(Dictionary<string, SurfaceItem> surface, string owner, FieldInfo field)
    {
        int access = FieldAccess(field);
        if (access == 0)
            return;
        FieldAttributes shape = field.Attributes & ~FieldAttributes.FieldAccessMask;
        string constant = field.IsLiteral ? "|" + ConstantShape(field.GetRawConstantValue()) : "";
        string key = "FIELD|" + owner + "|" + field.Name + "|" +
                ((int)shape).ToString(CultureInfo.InvariantCulture) + "|" +
                TypeIdentity(field.FieldType) + CustomModifiers(field) + constant;
        AddFact(surface, key, access);
        AddAttributes(surface, key, field.CustomAttributes, access);
    }

    private static void AddProperty(Dictionary<string, SurfaceItem> surface, string owner, PropertyInfo property)
    {
        int access = new[] { property.GetMethod, property.SetMethod }
            .Where(method => method != null)
            .Select(MethodAccess)
            .DefaultIfEmpty(0)
            .Max();
        if (access == 0)
            return;
        string indices = string.Join(",", property.GetIndexParameters().Select(ParameterShape));
        string key = "PROPERTY|" + owner + "|" + property.Name + "|" +
                ((int)property.Attributes).ToString(CultureInfo.InvariantCulture) + "|(" + indices + ")->" +
                TypeIdentity(property.PropertyType) + CustomModifiers(property);
        AddFact(surface, key, access);
        AddAttributes(surface, key, property.CustomAttributes, access);
    }

    private static void AddEvent(Dictionary<string, SurfaceItem> surface, string owner, EventInfo eventInfo)
    {
        int access = new[] { eventInfo.AddMethod, eventInfo.RemoveMethod, eventInfo.RaiseMethod }
            .Where(method => method != null)
            .Select(MethodAccess)
            .DefaultIfEmpty(0)
            .Max();
        if (access == 0)
            return;
        string key = "EVENT|" + owner + "|" + eventInfo.Name + "|" +
                ((int)eventInfo.Attributes).ToString(CultureInfo.InvariantCulture) + "|" +
                TypeIdentity(eventInfo.EventHandlerType);
        AddFact(surface, key, access);
        AddAttributes(surface, key, eventInfo.CustomAttributes, access);
    }

    private static void AddGenericParameters(
        Dictionary<string, SurfaceItem> surface,
        string owner,
        Type[] parameters,
        int access)
    {
        for (int index = 0; index < parameters.Length; index++)
        {
            string key = owner + "|" + index.ToString(CultureInfo.InvariantCulture) + "|" +
                GenericParameterShape(parameters[index]);
            AddFact(surface, key, access);
            AddAttributes(surface, key, parameters[index].CustomAttributes, access);
        }
    }

    private static string GenericParameterShape(Type parameter)
    {
        string constraints = string.Join(",", parameter.GetGenericParameterConstraints()
            .Select(TypeIdentity)
            .OrderBy(value => value, StringComparer.Ordinal));
        return ((int)parameter.GenericParameterAttributes).ToString(CultureInfo.InvariantCulture) +
            "[" + constraints + "]";
    }

    private static string GenericParameterShape(Type[] parameters) =>
        string.Join(";", parameters.Select(GenericParameterShape));

    private static string ParameterShape(ParameterInfo parameter)
    {
        string constant = (parameter.Attributes & ParameterAttributes.HasDefault) != 0
            ? "=" + ConstantShape(parameter.RawDefaultValue)
            : "";
        return ((int)parameter.Attributes).ToString(CultureInfo.InvariantCulture) + ":" +
            TypeIdentity(parameter.ParameterType) + CustomModifiers(parameter) + constant;
    }

    private static string ConstantShape(object value)
    {
        if (value == null)
            return "null";
        return value.GetType().FullName + ":" + Convert.ToString(value, CultureInfo.InvariantCulture);
    }

    private static string CustomModifiers(ParameterInfo parameter) =>
        CustomModifiers(parameter.GetRequiredCustomModifiers(), parameter.GetOptionalCustomModifiers());

    private static string CustomModifiers(FieldInfo field) =>
        CustomModifiers(field.GetRequiredCustomModifiers(), field.GetOptionalCustomModifiers());

    private static string CustomModifiers(PropertyInfo property) =>
        CustomModifiers(property.GetRequiredCustomModifiers(), property.GetOptionalCustomModifiers());

    private static string CustomModifiers(Type[] required, Type[] optional) =>
        "|req[" + string.Join(",", required.Select(TypeIdentity)) + "]|opt[" +
        string.Join(",", optional.Select(TypeIdentity)) + "]";

    private static void AddAttributes(
        Dictionary<string, SurfaceItem> surface,
        string owner,
        IEnumerable<CustomAttributeData> attributes,
        int access)
    {
        foreach (IGrouping<string, string> group in attributes
            .Select(CustomAttributeShape)
            .GroupBy(value => value, StringComparer.Ordinal)
            .OrderBy(value => value.Key, StringComparer.Ordinal))
        {
            AddFact(
                surface,
                "ATTRIBUTE|" + owner + "|" + group.Count().ToString(CultureInfo.InvariantCulture) +
                    "|" + group.Key,
                access);
        }
    }

    private static string CustomAttributeShape(CustomAttributeData attribute)
    {
        string constructorArguments = string.Join(",", attribute.ConstructorArguments.Select(AttributeArgumentShape));
        string namedArguments = string.Join(",", attribute.NamedArguments
            .OrderBy(argument => argument.MemberName, StringComparer.Ordinal)
            .ThenBy(argument => argument.IsField)
            .Select(argument => (argument.IsField ? "field:" : "property:") +
                Escape(argument.MemberName) + "=" + AttributeArgumentShape(argument.TypedValue)));
        return TypeIdentity(attribute.AttributeType) + "(" + constructorArguments + "){" + namedArguments + "}";
    }

    private static string AttributeArgumentShape(CustomAttributeTypedArgument argument)
    {
        string value;
        if (argument.Value == null)
        {
            value = "null";
        }
        else if (argument.Value is Type type)
        {
            value = TypeIdentity(type);
        }
        else if (argument.Value is IEnumerable<CustomAttributeTypedArgument> values)
        {
            value = "[" + string.Join(",", values.Select(AttributeArgumentShape)) + "]";
        }
        else
        {
            value = Escape(Convert.ToString(argument.Value, CultureInfo.InvariantCulture));
        }
        return TypeIdentity(argument.ArgumentType) + "=" + value;
    }

    private static string Escape(string value) =>
        value.Replace("\\", "\\\\").Replace("|", "\\|").Replace(",", "\\,");

    private static string TypeIdentity(Type type)
    {
        if (type == null)
            return "<null>";
        if (type.IsGenericParameter)
            return (type.DeclaringMethod == null ? "!" : "!!") +
                type.GenericParameterPosition.ToString(CultureInfo.InvariantCulture);
        if (type.IsByRef)
            return TypeIdentity(type.GetElementType()) + "&";
        if (type.IsPointer)
            return TypeIdentity(type.GetElementType()) + "*";
        if (type.IsArray)
            return TypeIdentity(type.GetElementType()) + "[" + new string(',', type.GetArrayRank() - 1) + "]";
        if (type.IsGenericType)
        {
            Type definition = type.GetGenericTypeDefinition();
            return NamedTypeIdentity(definition) + "<" +
                string.Join(",", type.GetGenericArguments().Select(TypeIdentity)) + ">";
        }
        return NamedTypeIdentity(type);
    }

    private static string NamedTypeIdentity(Type type) =>
        type.Assembly.GetName().Name + ":" + type.FullName;

    private static int TypeAccess(Type type)
    {
        int ownAccess;
        if (!type.IsNested)
            ownAccess = type.IsPublic ? 3 : 0;
        else if (type.IsNestedPublic)
            ownAccess = 3;
        else if (type.IsNestedFamily || type.IsNestedFamORAssem)
            ownAccess = 2;
        else
            ownAccess = 0;
        if (ownAccess == 0 || type.DeclaringType == null)
            return ownAccess;
        return Math.Min(ownAccess, TypeAccess(type.DeclaringType));
    }

    private static int MethodAccess(MethodBase method)
    {
        if (method.IsPublic)
            return 3;
        if (method.IsFamily || method.IsFamilyOrAssembly)
            return 2;
        return 0;
    }

    private static int FieldAccess(FieldInfo field)
    {
        if (field.IsPublic)
            return 3;
        if (field.IsFamily || field.IsFamilyOrAssembly)
            return 2;
        return 0;
    }

    private static void AddFact(Dictionary<string, SurfaceItem> surface, string key, int access) =>
        Add(surface, new SurfaceItem(key, access, isMethod: false, isAbstract: false, isFinal: false));

    private static void Add(Dictionary<string, SurfaceItem> surface, SurfaceItem item)
    {
        if (surface.ContainsKey(item.Key))
            throw new InvalidOperationException("Duplicate normalized surface item: " + item.Key);
        surface.Add(item.Key, item);
    }
}
