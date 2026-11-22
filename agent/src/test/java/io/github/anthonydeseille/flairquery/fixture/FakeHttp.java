package io.github.anthonydeseille.flairquery.fixture;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Dynamic-proxy request/response, enough for the agent's reflective reads. */
public final class FakeHttp {

    private FakeHttp() { }

    public static HttpServletRequest request(String method, String uri) {
        Map<String, Object> attributes = new HashMap<>();
        return (HttpServletRequest) Proxy.newProxyInstance(FakeHttp.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, m, args) -> switch (m.getName()) {
                    case "getMethod" -> method;
                    case "getRequestURI" -> uri;
                    case "getAttribute" -> attributes.get((String) args[0]);
                    case "setAttribute" -> { attributes.put((String) args[0], args[1]); yield null; }
                    case "removeAttribute" -> { attributes.remove((String) args[0]); yield null; }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "FakeRequest[" + method + " " + uri + "]";
                    default -> null;
                });
    }

    public static HttpServletResponse response() {
        return (HttpServletResponse) Proxy.newProxyInstance(FakeHttp.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class}, (proxy, m, args) -> switch (m.getName()) {
                    case "getStatus" -> 200;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
