package graphql.execution;

import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.PublicApi;
import graphql.execution.instrumentation.ExecutionStrategyInstrumentationContext;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters;
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters;
import graphql.execution.reactive.SubscriptionPublisher;
import graphql.language.Field;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import org.reactivestreams.Publisher;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static graphql.Assert.assertTrue;
import static graphql.execution.FieldValueInfo.CompleteValueType.OBJECT;
import static graphql.execution.instrumentation.SimpleInstrumentationContext.nonNullCtx;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.CompletableFuture.*;

/**
 * An execution strategy that implements graphql subscriptions by using reactive-streams
 * as the output result of the subscription query.
 * <p>
 * Afterwards each object delivered on that stream will be mapped via running the original selection set over that object and hence producing an ExecutionResult
 * just like a normal graphql query.
 * <p>
 * See https://github.com/facebook/graphql/blob/master/spec/Section%206%20--%20Execution.md
 * See http://www.reactive-streams.org/
 */
@PublicApi
public class SubscriptionExecutionStrategy extends ExecutionStrategy {

    public SubscriptionExecutionStrategy() {
        super();
    }

    public SubscriptionExecutionStrategy(DataFetcherExceptionHandler dataFetcherExceptionHandler) {
        super(dataFetcherExceptionHandler);
    }

    @Override
    public CompletableFuture<ExecutionResult> execute(ExecutionContext executionContext, ExecutionStrategyParameters parameters) throws NonNullableFieldWasNullException {

        Instrumentation instrumentation = executionContext.getInstrumentation();
        InstrumentationExecutionStrategyParameters instrumentationParameters = new InstrumentationExecutionStrategyParameters(executionContext, parameters);
        ExecutionStrategyInstrumentationContext executionStrategyCtx = ExecutionStrategyInstrumentationContext.nonNullCtx(instrumentation.beginExecutionStrategy(
                instrumentationParameters,
                executionContext.getInstrumentationState()
        ));

        CompletableFuture<Publisher<Object>> sourceEventStream = createSourceEventStream(executionContext, parameters, executionStrategyCtx);

        //
        // when the upstream source event stream completes, subscribe to it and wire in our adapter
        CompletableFuture<ExecutionResult> overallResult = sourceEventStream.thenApply((publisher) -> {

            if (publisher == null) {
                return new ExecutionResultImpl(null, executionContext.getErrors());
            }
            Function<Object, CompletionStage<ExecutionResult>> mapperFunction = eventPayload -> executeSubscriptionEvent(executionContext, parameters, eventPayload);
            SubscriptionPublisher mapSourceToResponse = new SubscriptionPublisher(publisher, mapperFunction);
            return new ExecutionResultImpl(mapSourceToResponse, executionContext.getErrors());
        });

        // dispatched the subscription query
        executionStrategyCtx.onDispatched(overallResult);
        overallResult.whenComplete(executionStrategyCtx::onCompleted);

        return overallResult;
    }


    /*
        https://github.com/facebook/graphql/blob/master/spec/Section%206%20--%20Execution.md

        CreateSourceEventStream(subscription, schema, variableValues, initialValue):

            Let {subscriptionType} be the root Subscription type in {schema}.
            Assert: {subscriptionType} is an Object type.
            Let {selectionSet} be the top level Selection Set in {subscription}.
            Let {rootField} be the first top level field in {selectionSet}.
            Let {argumentValues} be the result of {CoerceArgumentValues(subscriptionType, rootField, variableValues)}.
            Let {fieldStream} be the result of running {ResolveFieldEventStream(subscriptionType, initialValue, rootField, argumentValues)}.
            Return {fieldStream}.
     */

    private CompletableFuture<Publisher<Object>> createSourceEventStream(ExecutionContext executionContext, ExecutionStrategyParameters parameters, ExecutionStrategyInstrumentationContext executionStrategyCtx) {
        ExecutionStrategyParameters newParameters = firstFieldOfSubscriptionSelection(parameters);

        CompletableFuture<FetchedValue> fieldFetched = fetchField(executionContext, newParameters);

        return fieldFetched.thenApply(fetchedValue -> {

            Object publisherObj = fetchedValue.getFetchedValue();
            if (publisherObj != null) {
                assertTrue(publisherObj instanceof Publisher, () -> "Your data fetcher must return a Publisher of events when using graphql subscriptions");
            }

            //noinspection unchecked
            Publisher<Object> publisher = (Publisher<Object>) publisherObj;

            // so instrumentation knows the field has completed to a resolved value
            invokeOnFieldValuesInfo(executionStrategyCtx, publisher);

            return publisher;
        });
    }

    private static void invokeOnFieldValuesInfo(ExecutionStrategyInstrumentationContext executionStrategyCtx, Object value) {
        // this will tell instrumentation that the subscription field publisher has been fetched and completed
        ExecutionResult result = ExecutionResultImpl.newExecutionResult().data(value).build();
        FieldValueInfo fieldValueInfo = FieldValueInfo.newFieldValueInfo(OBJECT).fieldValue(completedFuture(result)).build();

        executionStrategyCtx.onFieldValuesInfo(Collections.singletonList(fieldValueInfo));
    }

    /*
        ExecuteSubscriptionEvent(subscription, schema, variableValues, initialValue):

        Let {subscriptionType} be the root Subscription type in {schema}.
        Assert: {subscriptionType} is an Object type.
        Let {selectionSet} be the top level Selection Set in {subscription}.
        Let {data} be the result of running {ExecuteSelectionSet(selectionSet, subscriptionType, initialValue, variableValues)} normally (allowing parallelization).
        Let {errors} be any field errors produced while executing the selection set.
        Return an unordered map containing {data} and {errors}.

        Note: The {ExecuteSubscriptionEvent()} algorithm is intentionally similar to {ExecuteQuery()} since this is how each event result is produced.
     */

    private CompletableFuture<ExecutionResult> executeSubscriptionEvent(ExecutionContext executionContext, ExecutionStrategyParameters parameters, Object eventPayload) {
        Instrumentation instrumentation = executionContext.getInstrumentation();

        ExecutionContext newExecutionContext = executionContext.transform(builder -> builder
                .root(eventPayload)
                .resetErrors()
        );
        ExecutionStrategyParameters newParameters = firstFieldOfSubscriptionSelection(parameters);
        ExecutionStepInfo subscribedFieldStepInfo = createSubscribedFieldStepInfo(executionContext, newParameters);

        InstrumentationFieldParameters i13nFieldParameters = new InstrumentationFieldParameters(executionContext, () -> subscribedFieldStepInfo);
        InstrumentationContext<ExecutionResult> subscribedFieldCtx = nonNullCtx(instrumentation.beginSubscribedFieldEvent(
                i13nFieldParameters, executionContext.getInstrumentationState()
        ));

        FetchedValue fetchedValue = unboxPossibleDataFetcherResult(newExecutionContext, parameters, eventPayload);
        FieldValueInfo fieldValueInfo = completeField(newExecutionContext, newParameters, fetchedValue);
        CompletableFuture<ExecutionResult> overallResult = fieldValueInfo
                .getFieldValue()
                .thenApply(executionResult -> wrapWithRootFieldName(newParameters, executionResult));

        // dispatch instrumentation so they can know about each subscription event
        subscribedFieldCtx.onDispatched(overallResult);
        overallResult.whenComplete(subscribedFieldCtx::onCompleted);

        // allow them to instrument each ER should they want to
        InstrumentationExecutionParameters i13nExecutionParameters = new InstrumentationExecutionParameters(
                executionContext.getExecutionInput(), executionContext.getGraphQLSchema(), executionContext.getInstrumentationState());

        overallResult = overallResult.thenCompose(executionResult -> instrumentation.instrumentExecutionResult(executionResult, i13nExecutionParameters, executionContext.getInstrumentationState()));
        return overallResult;
    }

    private ExecutionResult wrapWithRootFieldName(ExecutionStrategyParameters parameters, ExecutionResult executionResult) {
        String rootFieldName = getRootFieldName(parameters);
        return new ExecutionResultImpl(
                singletonMap(rootFieldName, executionResult.getData()),
                executionResult.getErrors()
        );
    }

    private String getRootFieldName(ExecutionStrategyParameters parameters) {
        Field rootField = parameters.getField().getSingleField();
        return rootField.getResultKey();
    }

    private ExecutionStrategyParameters firstFieldOfSubscriptionSelection(ExecutionStrategyParameters parameters) {
        MergedSelectionSet fields = parameters.getFields();
        MergedField firstField = fields.getSubField(fields.getKeys().get(0));

        ResultPath fieldPath = parameters.getPath().segment(mkNameForPath(firstField.getSingleField()));
        return parameters.transform(builder -> builder.field(firstField).path(fieldPath));
    }

    private ExecutionStepInfo createSubscribedFieldStepInfo(ExecutionContext executionContext, ExecutionStrategyParameters parameters) {
        Field field = parameters.getField().getSingleField();
        GraphQLObjectType parentType = (GraphQLObjectType) parameters.getExecutionStepInfo().getUnwrappedNonNullType();
        GraphQLFieldDefinition fieldDef = getFieldDef(executionContext.getGraphQLSchema(), parentType, field);
        return createExecutionStepInfo(executionContext, parameters, fieldDef, parentType);
    }
}
