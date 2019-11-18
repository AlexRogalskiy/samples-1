package webflux;

import graphql.ExecutionResult;
import graphql.kickstart.execution.GraphQLInvoker;
import graphql.kickstart.execution.GraphQLObjectMapper;
import graphql.kickstart.execution.GraphQLRequest;
import graphql.kickstart.execution.input.GraphQLInvocationInput;
import graphql.kickstart.execution.input.GraphQLSingleInvocationInput;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class GraphQLController {

  @Autowired
  private GraphQLInvoker graphQLInvoker;

  @Autowired
  private GraphQLObjectMapper objectMapper;

  @Autowired
  private GraphQLInvocationInputFactory graphQLInvocationInputFactory;

  @RequestMapping(value = "${graphql.url:graphql}",
      method = RequestMethod.POST,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public Object graphqlPOST(
      @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
      @RequestParam(value = "query", required = false) String query,
      @RequestParam(value = "operationName", required = false) String operationName,
      @RequestParam(value = "variables", required = false) String variablesJson,
      @RequestBody(required = false) String body,
      ServerWebExchange serverWebExchange) throws IOException {

    if (body == null) {
      body = "";
    }

    // https://graphql.org/learn/serving-over-http/#post-request
    //
    // A standard GraphQL POST request should use the application/json content type,
    // and include a JSON-encoded body of the following form:
    //
    // {
    //   "query": "...",
    //   "operationName": "...",
    //   "variables": { "myVariable": "someValue", ... }
    // }

    if (MediaType.APPLICATION_JSON_VALUE.equals(contentType)) {
      GraphQLRequest request = objectMapper.readGraphQLRequest(body);
      if (request.getQuery() == null) {
        request.setQuery("");
      }
      return executeRequest(request.getQuery(), request.getOperationName(), request.getVariables(), serverWebExchange);
    }

    // In addition to the above, we recommend supporting two additional cases:
    //
    // * If the "query" query string parameter is present (as in the GET example above),
    //   it should be parsed and handled in the same way as the HTTP GET case.

    if (query != null) {
      return executeRequest(query, operationName, convertVariablesJson(variablesJson), serverWebExchange);
    }

    // * If the "application/graphql" Content-Type header is present,
    //   treat the HTTP POST body contents as the GraphQL query string.

    if ("application/graphql".equals(contentType)) {
      return executeRequest(body, null, null, serverWebExchange);
    }

    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Could not process GraphQL request");
  }

  @RequestMapping(value = "${graphql.url:graphql}",
      method = RequestMethod.GET,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public Object graphqlGET(
      @RequestParam("query") String query,
      @RequestParam(value = "operationName", required = false) String operationName,
      @RequestParam(value = "variables", required = false) String variablesJson,
      ServerWebExchange serverWebExchange) {

    // https://graphql.org/learn/serving-over-http/#get-request
    //
    // When receiving an HTTP GET request, the GraphQL query should be specified in the "query" query string.
    // For example, if we wanted to execute the following GraphQL query:
    //
    // {
    //   me {
    //     name
    //   }
    // }
    //
    // This request could be sent via an HTTP GET like so:
    //
    // http://myapi/graphql?query={me{name}}
    //
    // Query variables can be sent as a JSON-encoded string in an additional query parameter called "variables".
    // If the query contains several named operations,
    // an "operationName" query parameter can be used to control which one should be executed.

    return executeRequest(query, operationName, convertVariablesJson(variablesJson), serverWebExchange);
  }

  private Map<String, Object> convertVariablesJson(String jsonMap) {
    if (jsonMap == null) {
      return Collections.emptyMap();
    }
    return objectMapper.deserializeVariables(jsonMap);
  }

  private Object executeRequest(
      String query,
      String operationName,
      Map<String, Object> variables,
      ServerWebExchange serverWebExchange) {
    GraphQLSingleInvocationInput invocationInput = graphQLInvocationInputFactory.create(query, operationName, variables);
    Mono<ExecutionResult> executionResult = Mono.fromCompletionStage(graphQLInvoker.executeAsync(invocationInput));
    return executionResult.map(ExecutionResult::toSpecification);
  }

}
