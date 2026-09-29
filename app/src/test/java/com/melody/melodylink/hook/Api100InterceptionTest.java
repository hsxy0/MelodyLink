package com.melody.melodylink.hook;

import static org.junit.Assert.*;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.junit.Before;
import org.junit.Test;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Verifies original-call and protective-fallback semantics without an Android process. @author WU */
public class Api100InterceptionTest {
    private int calls;
    private XposedModule module;

    @Before public void setUp() {
        XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(), new Class<?>[]{XposedInterface.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("invokeOrigin")) {
                        calls++;
                        return ((Method) args[0]).invoke(args[1], (Object[]) args[2]);
                    }
                    return null;
                });
        module = new XposedModule(framework, null) { };
    }

    @Test public void preservesArgumentsReceiverAndModifiedResult() throws Exception {
        Call call = install("normal", chain -> {
            assertSame(this, chain.getThisObject());
            assertEquals("value", chain.getArg(0));
            return chain.proceed() + "!";
        });
        assertEquals("value!", call.result);
        assertEquals(1, calls);
        assertTrue(call.skipped);
    }

    @Test public void shortCircuitDoesNotRunOriginal() throws Exception {
        Call call = install("shortCircuit", chain -> "replaced");
        assertEquals("replaced", call.result);
        assertEquals(0, calls);
    }

    @Test public void originalExceptionIsUnwrapped() throws Exception {
        Call call = install("throwsOriginal", Api100Interception.Chain::proceed);
        assertSame(TARGET_FAILURE, call.failure);
        assertEquals(1, calls);
    }

    @Test public void beforeFailureLetsFrameworkRunOriginal() throws Exception {
        Call call = install("beforeFailure", chain -> { throw new IllegalStateException("hook"); });
        assertFalse(call.skipped);
        assertEquals(0, calls);
    }

    @Test public void afterFailureKeepsOriginalResultWithoutRetry() throws Exception {
        Call call = install("afterFailure", chain -> {
            chain.proceed();
            throw new IllegalStateException("hook");
        });
        assertEquals("value", call.result);
        assertEquals(1, calls);
        assertTrue(call.skipped);
    }

    @Test public void repeatedProceedDoesNotRepeatSideEffects() throws Exception {
        Call call = install("repeat", chain -> {
            chain.proceed();
            return chain.proceed();
        });
        assertEquals("value", call.result);
        assertEquals(1, calls);
    }

    @Test public void nullResultIsStillAnIntentionalSkip() throws Exception {
        Call call = install("nullResult", chain -> null);
        assertTrue(call.skipped);
        assertNull(call.result);
        assertEquals(0, calls);
    }

    private Call install(String name, Api100Interception.Interceptor interceptor) throws Exception {
        Method method = getClass().getMethod(name, String.class);
        Api100Interception.install(module, method, interceptor);
        Call call = new Call(method, this);
        Api100Interception.Callback.before(call);
        return call;
    }

    private static final IllegalArgumentException TARGET_FAILURE = new IllegalArgumentException("target");
    public String normal(String value) { return value; }
    public String shortCircuit(String value) { return value; }
    public String throwsOriginal(String value) { throw TARGET_FAILURE; }
    public String beforeFailure(String value) { return value; }
    public String afterFailure(String value) { return value; }
    public String repeat(String value) { return value; }
    public String nullResult(String value) { return value; }

    /** Minimal framework callback fixture. @author WU */
    private static final class Call implements XposedInterface.BeforeHookCallback {
        private final Method method;
        private final Object receiver;
        Object result;
        Throwable failure;
        boolean skipped;

        Call(Method method, Object receiver) { this.method = method; this.receiver = receiver; }
        public Member getMember() { return method; }
        public Object getThisObject() { return receiver; }
        public Object[] getArgs() { return new Object[]{"value"}; }
        public void returnAndSkip(Object value) { skipped = true; result = value; }
        public void throwAndSkip(Throwable value) { skipped = true; failure = value; }
    }
}
