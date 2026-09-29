package com.melody.melodylink.hook;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Adapts Melody method interceptors to API 100 static callbacks.
 * @author WU
 */
public final class Api100Interception {
    private static final Map<Member, Entry> ENTRIES = new ConcurrentHashMap<>();

    private Api100Interception() { }

    /** One target-method interception. @author WU */
    @FunctionalInterface
    public interface Interceptor {
        Object intercept(Chain chain) throws Throwable;
    }

    /** Registered module and its callback. @author WU */
    private static final class Entry {
        final XposedModule module;
        final Interceptor interceptor;

        Entry(XposedModule module, Interceptor interceptor) {
            this.module = module;
            this.interceptor = interceptor;
        }
    }

    /** Registers the API 100 callback before exposing it to the framework. */
    public static void install(XposedModule module, Method method, Interceptor interceptor) {
        Entry entry = new Entry(module, interceptor);
        if (ENTRIES.putIfAbsent(method, entry) != null) return;
        try {
            module.hook(method, Callback.class);
        } catch (RuntimeException | Error failure) {
            ENTRIES.remove(method, entry);
            throw failure;
        }
    }

    /** Per-invocation arguments and original outcome for protective fallback. @author WU */
    public static final class Chain {
        private final XposedInterface.BeforeHookCallback callback;
        private final XposedModule module;
        private boolean proceeded;
        private Object result;
        private Throwable failure;

        private Chain(XposedInterface.BeforeHookCallback callback, XposedModule module) {
            this.callback = callback;
            this.module = module;
        }

        public Object getArg(int index) { return callback.getArgs()[index]; }

        public Object getThisObject() { return callback.getThisObject(); }

        /** Invokes the original once and propagates the target exception without reflection wrapping. */
        public Object proceed() throws Throwable {
            if (!proceeded) {
                proceeded = true;
                try {
                    result = module.invokeOrigin((Method) callback.getMember(),
                            callback.getThisObject(), callback.getArgs());
                } catch (InvocationTargetException wrapped) {
                    failure = wrapped.getCause();
                } catch (Throwable thrown) {
                    failure = thrown;
                }
            }
            if (failure != null) throw failure;
            return result;
        }
    }

    /** Public static entry point called by API 100. @author WU */
    public static final class Callback implements XposedInterface.Hooker {
        private Callback() { }

        /** Keeps target failures intact and avoids repeating originals after interceptor failures. */
        public static void before(XposedInterface.BeforeHookCallback callback) {
            Entry entry = ENTRIES.get(callback.getMember());
            if (entry == null) return;
            Chain chain = new Chain(callback, entry.module);
            try {
                callback.returnAndSkip(entry.interceptor.intercept(chain));
            } catch (Throwable failure) {
                if (chain.failure != failure) {
                    entry.module.log(6, "MelodyLinkObserver", "API 100 interceptor failed", failure);
                }
                if (chain.proceeded) {
                    if (chain.failure != null) callback.throwAndSkip(chain.failure);
                    else callback.returnAndSkip(chain.result);
                }
                // If interception failed before proceed(), let the framework call the original.
            }
        }
    }
}
