package com.bumenfeld.util;

import java.lang.reflect.Field;

public final class ReflectionUtil {

    private ReflectionUtil() {
        // utility class
    }

    public static <T> T getPublic(
        Class<T> classZ,
        Object object,
        String fieldName
    ) {
        try {
            Field field = object.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            T value = (T) field.get(object);
            return value;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }
}
