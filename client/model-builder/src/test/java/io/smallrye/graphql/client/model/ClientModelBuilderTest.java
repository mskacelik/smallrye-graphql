package io.smallrye.graphql.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Query;
import org.jboss.jandex.Index;
import org.junit.jupiter.api.Test;

import io.smallrye.graphql.api.Subscription;
import io.smallrye.graphql.client.typesafe.api.GraphQLClientApi;

/**
 * @author mskacelik
 */
public class ClientModelBuilderTest {

    @GraphQLClientApi(configKey = "scalar")
    interface ScalarClientApi {
        @Query
        Integer returnInteger(Integer someNumber);

        @Mutation
        String returnString(String someString);

        @Subscription
        float returnNonNullFloat(float someFloat);
    }

    @Test
    void sclarClientModelTest() throws IOException {
        String configKey = "scalar";
        ClientModels clientModels = ClientModelBuilder.build(Index.of(ScalarClientApi.class));
        assertNotNull(clientModels.getClientModelByConfigKey(configKey));
        ClientModel clientModel = clientModels.getClientModelByConfigKey(configKey);
        assertEquals(3, clientModel.getOperationMap().size());
        assertOperation(clientModel,
                new MethodKey(Integer.class, "returnInteger", new Class<?>[] { Integer.class }),
                "query returnInteger($someNumber: Int) { returnInteger(someNumber: $someNumber) }");

        assertOperation(clientModel,
                new MethodKey(String.class, "returnString", new Class<?>[] { String.class }),
                "mutation returnString($someString: String) { returnString(someString: $someString) }");

        assertOperation(clientModel,
                new MethodKey(float.class, "returnNonNullFloat", new Class<?>[] { float.class }),
                "subscription returnNonNullFloat($someFloat: Float!) { returnNonNullFloat(someFloat: $someFloat) }");
    }

    @GraphQLClientApi(configKey = "collection")
    interface CollectionClientApi {
        @Query
        Collection<Integer> returnIntegerCollection(Integer[] someNumbers);

        @Mutation
        List<String> returnStringList(Set<String> someStrings);

        @Subscription
        ArrayList<Float> returnFloatArrayList(HashSet<Float> someFloats);
    }

    @Test
    void collectionClientModelTest() throws IOException {
        String configKey = "collection";
        ClientModels clientModels = ClientModelBuilder.build(Index.of(CollectionClientApi.class));
        assertNotNull(clientModels.getClientModelByConfigKey(configKey));
        ClientModel clientModel = clientModels.getClientModelByConfigKey(configKey);
        assertEquals(3, clientModel.getOperationMap().size());
        assertOperation(clientModel,
                new MethodKey(Collection.class, "returnIntegerCollection", new Class<?>[] { Integer[].class }),
                "query returnIntegerCollection($someNumbers: [Int]) { returnIntegerCollection(someNumbers: $someNumbers) }");

        assertOperation(clientModel,
                new MethodKey(List.class, "returnStringList", new Class<?>[] { Set.class }),
                "mutation returnStringList($someStrings: [String]) { returnStringList(someStrings: $someStrings) }");

        assertOperation(clientModel,
                new MethodKey(ArrayList.class, "returnFloatArrayList", new Class<?>[] { HashSet.class }),
                "subscription returnFloatArrayList($someFloats: [Float]) { returnFloatArrayList(someFloats: $someFloats) }");
    }

    @Test
    void simpleObjectClientModelTest() throws IOException {
        String configKey = "simple-object";
        ClientModels clientModels = ClientModelBuilder.build(Index.of(SimpleObjectClientApi.class,
                SimpleObjectClientApi.SomeObject.class, SimpleObjectClientApi.SomeObject.InnerClass.class));
        assertNotNull(clientModels.getClientModelByConfigKey(configKey));
        ClientModel clientModel = clientModels.getClientModelByConfigKey(configKey);
        assertEquals(1, clientModel.getOperationMap().size());
        assertOperation(clientModel,
                new MethodKey(SimpleObjectClientApi.SomeObject.class, "returnSomeObject",
                        new Class<?>[] { SimpleObjectClientApi.SomeObject.class }),
                "query returnSomeObject($someObject: SomeObjectInput) { returnSomeObject(someObject: $someObject) {name innerObject {someInt}} }");
    }

    @GraphQLClientApi(configKey = "simple-object")
    interface SimpleObjectClientApi {
        @Query
        SomeObject returnSomeObject(SomeObject someObject);

        class SomeObject {
            String name;
            InnerClass innerObject;

            static class InnerClass {
                int someInt;
            }
        }
    }

    @Test
    void complexObjectClientModelTest() throws IOException {
        String configKey = "complex-object";
        ClientModels clientModels = ClientModelBuilder
                .build(Index.of(ComplexObjectClientApi.class, ComplexObjectClientApi.SomeGenericClass.class,
                        ComplexObjectClientApi.SomeGenericClass.InnerClass.class, ComplexObjectClientApi.SomeObjectOne.class,
                        ComplexObjectClientApi.SomeObjectTwo.class,
                        ComplexObjectClientApi.SomeObjectThree.class,
                        ComplexObjectClientApi.SomeObjectFour.class,
                        ComplexObjectClientApi.SomeObjectFive.class));
        assertNotNull(clientModels.getClientModelByConfigKey(configKey));
        ClientModel clientModel = clientModels.getClientModelByConfigKey(configKey);
        assertEquals(1, clientModel.getOperationMap().size());
        assertOperation(clientModel,
                new MethodKey(List.class, "returnSomeGenericObject", new Class<?>[] { List.class }),
                "query returnSomeGenericObject($someObject: [SomeGenericClassInput])" +
                        " { returnSomeGenericObject(someObject: $someObject) {somethingParent {number}" +
                        " field1 {number} field2 {innerField {number} something {someObject {someThingElse" +
                        " {string}}}}} }");
    }

    @GraphQLClientApi(configKey = "complex-object")
    interface ComplexObjectClientApi {
        @Query
        List<SomeGenericClass<SomeObjectOne>> returnSomeGenericObject(List<SomeGenericClass<SomeObjectOne>> someObject);

        class SomeGenericClass<T> extends SomeObjectFour<T> {
            T field1;
            InnerClass<T> field2;

            static class InnerClass<N> {
                N innerField;

                SomeObjectTwo<List<SomeObjectFive<SomeObjectThree>>> something;
            }
        }

        class SomeObjectOne {
            int number;
        }

        class SomeObjectTwo<T> {
            T someObject;
        }

        class SomeObjectThree {
            String string;
        }

        class SomeObjectFour<T> {
            T somethingParent;
        }

        class SomeObjectFive<S> {
            S someThingElse;
        }
    }

    private void assertOperation(ClientModel clientModel, MethodKey methodKey, String expectedQuery) {
        String actualQuery = clientModel.getOperationMap().get(methodKey);
        assertEquals(expectedQuery, actualQuery);
    }

}
