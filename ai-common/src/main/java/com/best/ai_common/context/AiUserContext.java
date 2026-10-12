package com.best.ai_common.context;

import java.util.concurrent.Callable;

public final class AiUserContext {

    public static final String ANONYMOUS = "anonymous";

    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    private AiUserContext(){}

    public static void set(String userId){
        CURRENT_USER.set(userId);
    }

    public static String get(){
        return CURRENT_USER.get();
    }

    public static void clear(){
        CURRENT_USER.remove();
    }

    public static Scope open(String userId) {
        String previous = CURRENT_USER.get();
        CURRENT_USER.set(userId);
        return () -> {
            if (previous == null) {
                CURRENT_USER.remove();
            } else {
                CURRENT_USER.set(previous);
            }
        };
    }

    public static Runnable wrap(Runnable task){
        String userId = get();
        return () -> {
            try(Scope ignored = open(userId)) {
                task.run();
            }
        };
    }

    public static <T> Callable<T> wrap(Callable<T> task){
        String userId = get();
        return () -> {
            try (Scope ignored = open(userId)){
                return task.call();
            }
        };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable{
        @Override
        void close();
    }
}
