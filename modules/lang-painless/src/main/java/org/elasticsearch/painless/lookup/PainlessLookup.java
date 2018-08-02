/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.painless.lookup;

import java.lang.invoke.MethodHandle;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.elasticsearch.painless.lookup.PainlessLookupUtility.buildPainlessConstructorKey;
import static org.elasticsearch.painless.lookup.PainlessLookupUtility.buildPainlessFieldKey;
import static org.elasticsearch.painless.lookup.PainlessLookupUtility.buildPainlessMethodKey;
import static org.elasticsearch.painless.lookup.PainlessLookupUtility.typeToBoxedType;
import static org.elasticsearch.painless.lookup.PainlessLookupUtility.typeToCanonicalTypeName;

public final class PainlessLookup {

    private final Map<String, Class<?>> canonicalClassNamesToClasses;
    private final Map<Class<?>, PainlessClass> classesToPainlessClasses;

    private final List<Class<?>> sortedClassesByCanonicalClassName;

    PainlessLookup(Map<String, Class<?>> canonicalClassNamesToClasses, Map<Class<?>, PainlessClass> classesToPainlessClasses) {
        Objects.requireNonNull(canonicalClassNamesToClasses);
        Objects.requireNonNull(classesToPainlessClasses);

        this.canonicalClassNamesToClasses = Collections.unmodifiableMap(canonicalClassNamesToClasses);
        this.classesToPainlessClasses = Collections.unmodifiableMap(classesToPainlessClasses);

        this.sortedClassesByCanonicalClassName = Collections.unmodifiableList(
                classesToPainlessClasses.keySet().stream().sorted(
                        Comparator.comparing(Class::getCanonicalName)
                ).collect(Collectors.toList())
        );
    }

    public boolean isValidCanonicalClassName(String canonicalClassName) {
        Objects.requireNonNull(canonicalClassName);

        return canonicalClassNamesToClasses.containsKey(canonicalClassName);
    }

    public Class<?> canonicalTypeNameToType(String canonicalTypeName) {
        Objects.requireNonNull(canonicalTypeName);

        return PainlessLookupUtility.canonicalTypeNameToType(canonicalTypeName, canonicalClassNamesToClasses);
    }

    public List<Class<?>> getSortedClassesByCanonicalClassName() {
        return sortedClassesByCanonicalClassName;
    }

    public PainlessClass lookupPainlessClass(Class<?> targetClass) {
        return classesToPainlessClasses.get(targetClass);
    }

    public PainlessConstructor lookupPainlessConstructor(String targetCanonicalClassName, int constructorArity) {
        Objects.requireNonNull(targetCanonicalClassName);

        Class<?> targetClass = canonicalTypeNameToType(targetCanonicalClassName);

        return lookupPainlessConstructor(targetClass, constructorArity);
    }

    public PainlessConstructor lookupPainlessConstructor(Class<?> targetClass, int constructorArity) {
        Objects.requireNonNull(targetClass);

        PainlessClass targetPainlessClass = classesToPainlessClasses.get(targetClass);
        String painlessConstructorKey = buildPainlessConstructorKey(constructorArity);

        if (targetPainlessClass == null) {
            throw new IllegalArgumentException("target class [" + typeToCanonicalTypeName(targetClass) + "] " +
                    "not found for constructor [" + painlessConstructorKey + "]");
        }

        PainlessConstructor painlessConstructor = targetPainlessClass.constructors.get(painlessConstructorKey);

        if (painlessConstructor == null) {
            throw new IllegalArgumentException(
                    "constructor [" + typeToCanonicalTypeName(targetClass) + ", " + painlessConstructorKey + "] not found");
        }

        return painlessConstructor;
    }

    public PainlessMethod lookupPainlessMethod(String targetCanonicalClassName, boolean isStatic, String methodName, int methodArity) {
        Objects.requireNonNull(targetCanonicalClassName);

        Class<?> targetClass = canonicalTypeNameToType(targetCanonicalClassName);

        return lookupPainlessMethod(targetClass, isStatic, methodName, methodArity);
    }

    public PainlessMethod lookupPainlessMethod(Class<?> targetClass, boolean isStatic, String methodName, int methodArity) {
        Objects.requireNonNull(targetClass);
        Objects.requireNonNull(methodName);

        if (targetClass.isPrimitive()) {
            targetClass = typeToBoxedType(targetClass);
        }

        PainlessClass targetPainlessClass = classesToPainlessClasses.get(targetClass);
        String painlessMethodKey = buildPainlessMethodKey(methodName, methodArity);

        if (targetPainlessClass == null) {
            throw new IllegalArgumentException(
                    "target class [" + typeToCanonicalTypeName(targetClass) + "] not found for method [" + painlessMethodKey + "]");
        }

        PainlessMethod painlessMethod = isStatic ?
                targetPainlessClass.staticMethods.get(painlessMethodKey) :
                targetPainlessClass.methods.get(painlessMethodKey);

        if (painlessMethod == null) {
            throw new IllegalArgumentException(
                    "method [" + typeToCanonicalTypeName(targetClass) + ", " + painlessMethodKey + "] not found");
        }

        return painlessMethod;
    }

    public PainlessField lookupPainlessField(String targetCanonicalClassName, boolean isStatic, String fieldName) {
        Objects.requireNonNull(targetCanonicalClassName);

        Class<?> targetClass = canonicalTypeNameToType(targetCanonicalClassName);

        return lookupPainlessField(targetClass, isStatic, fieldName);
    }

    public PainlessField lookupPainlessField(Class<?> targetClass, boolean isStatic, String fieldName) {
        Objects.requireNonNull(targetClass);
        Objects.requireNonNull(fieldName);

        PainlessClass targetPainlessClass = classesToPainlessClasses.get(targetClass);
        String painlessFieldKey = buildPainlessFieldKey(fieldName);

        if (targetPainlessClass == null) {
            throw new IllegalArgumentException(
                    "target class [" + typeToCanonicalTypeName(targetClass) + "] not found for field [" + painlessFieldKey + "]");
        }

        PainlessField painlessField = isStatic ?
                targetPainlessClass.staticFields.get(painlessFieldKey) :
                targetPainlessClass.fields.get(painlessFieldKey);

        if (painlessField == null) {
            throw new IllegalArgumentException(
                    "field [" + typeToCanonicalTypeName(targetClass) + ", " + painlessFieldKey + "] not found");
        }

        return painlessField;
    }

    public PainlessMethod lookupFunctionalInterfacePainlessMethod(Class<?> targetClass) {
        PainlessClass targetPainlessClass = classesToPainlessClasses.get(targetClass);

        if (targetPainlessClass == null) {
            throw new IllegalArgumentException("target class [" + typeToCanonicalTypeName(targetClass) + "] not found");
        }

        PainlessMethod functionalInterfacePainlessMethod = targetPainlessClass.functionalInterfaceMethod;

        if (functionalInterfacePainlessMethod == null) {
            throw new IllegalArgumentException("target class [" + typeToCanonicalTypeName(targetClass) + "] is not a functional interface");
        }

        return functionalInterfacePainlessMethod;
    }

    public PainlessMethod lookupRuntimePainlessMethod(Class<?> originalTargetClass, String methodName, int methodArity) {
        Objects.requireNonNull(originalTargetClass);
        Objects.requireNonNull(methodName);

        String painlessMethodKey = buildPainlessMethodKey(methodName, methodArity);
        Function<PainlessClass, PainlessMethod> objectLookup =
                targetPainlessClass -> targetPainlessClass.methods.get(painlessMethodKey);
        String notFoundErrorMessage =
                "dynamic method [" + typeToCanonicalTypeName(originalTargetClass) + ", " + painlessMethodKey + "] not found";

        return lookupRuntimePainlessObject(originalTargetClass, objectLookup, notFoundErrorMessage);
    }

    public MethodHandle lookupRuntimeGetterMethodHandle(Class<?> originalTargetClass, String getterName) {
        Objects.requireNonNull(originalTargetClass);
        Objects.requireNonNull(getterName);

        Function<PainlessClass, MethodHandle> objectLookup =
                targetPainlessClass -> targetPainlessClass.getterMethodHandles.get(getterName);
        String notFoundErrorMessage =
                "dynamic getter [" + typeToCanonicalTypeName(originalTargetClass) + ", " + getterName + "] not found";

        return lookupRuntimePainlessObject(originalTargetClass, objectLookup, notFoundErrorMessage);
    }

    public MethodHandle lookupRuntimeSetterMethodHandle(Class<?> originalTargetClass, String setterName) {
        Objects.requireNonNull(originalTargetClass);
        Objects.requireNonNull(setterName);

        Function<PainlessClass, MethodHandle> objectLookup =
                targetPainlessClass -> targetPainlessClass.setterMethodHandles.get(setterName);
        String notFoundErrorMessage =
                "dynamic setter [" + typeToCanonicalTypeName(originalTargetClass) + ", " + setterName + "] not found";

        return lookupRuntimePainlessObject(originalTargetClass, objectLookup, notFoundErrorMessage);
    }

    private <T> T lookupRuntimePainlessObject(
            Class<?> originalTargetClass, Function<PainlessClass, T> objectLookup, String notFoundErrorMessage) {

        Class<?> currentTargetClass = originalTargetClass;

        while (currentTargetClass != null) {
            PainlessClass targetPainlessClass = classesToPainlessClasses.get(currentTargetClass);

            if (targetPainlessClass != null) {
                T painlessObject = objectLookup.apply(targetPainlessClass);

                if (painlessObject != null) {
                    return painlessObject;
                }
            }

            currentTargetClass = currentTargetClass.getSuperclass();
        }

        currentTargetClass = originalTargetClass;

        while (currentTargetClass != null) {
            for (Class<?> targetInterface : currentTargetClass.getInterfaces()) {
                PainlessClass targetPainlessClass = classesToPainlessClasses.get(targetInterface);

                if (targetPainlessClass != null) {
                    T painlessObject = objectLookup.apply(targetPainlessClass);

                    if (painlessObject != null) {
                        return painlessObject;
                    }
                }
            }

            currentTargetClass = currentTargetClass.getSuperclass();
        }

        throw new IllegalArgumentException(notFoundErrorMessage);
    }
}
