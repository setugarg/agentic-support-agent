package dev.setugarg.supportagent.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * AWS Lambda entry point. Lambda Function URLs use the same payload format (v2.0) as API Gateway
 * HTTP APIs, so this handler works behind either.
 *
 * <p>Everything expensive (SDK clients, BM25 index, prompt) is built in the static initializer so
 * it runs once per execution environment - and with SnapStart, once per published version.
 */
public final class LambdaHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final App APP = App.fromEnv();
    private static final ChatService SERVICE = new ChatService(APP);

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        var http = event.getRequestContext().getHttp();
        String body = event.getBody();
        if (body != null && event.getIsBase64Encoded()) {
            body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
        }
        Map<String, String> headers = event.getHeaders() == null ? Map.of() : event.getHeaders();
        ChatService.HttpResult r = SERVICE.handle(http.getMethod(), http.getPath(), headers, body);
        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(r.status())
                .withHeaders(Map.of(
                        "content-type", r.contentType(),
                        "cache-control", "no-store",
                        "x-content-type-options", "nosniff",
                        "referrer-policy", "no-referrer"))
                .withBody(r.body())
                .build();
    }
}
