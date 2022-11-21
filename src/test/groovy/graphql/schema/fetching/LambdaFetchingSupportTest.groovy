package graphql.schema.fetching

import spock.lang.Specification

class LambdaFetchingSupportTest extends Specification {

    def "can proxy Pojo methods"() {

        def pojo = new Pojo("Brad", 42)
        when:
        def getName = LambdaFetchingSupport.mkCallFunction(Pojo.class, "getName", String.class)
        def getAge = LambdaFetchingSupport.mkCallFunction(Pojo.class, "getAge", Integer.TYPE)

        then:
        getName.apply(pojo) == "Brad"
        getAge.apply(pojo) == 42
    }

    def "get make getters based on property names"() {
        def pojo = new Pojo("Brad", 42)
        when:
        def getter = LambdaFetchingSupport.createGetter(Pojo.class, "name")
        then:
        getter.isPresent()
        getter.get().apply(pojo) == "Brad"

        when:
        getter = LambdaFetchingSupport.createGetter(Pojo.class, "age")
        then:
        getter.isPresent()
        getter.get().apply(pojo) == 42

    }

    def "get make getters based on record like names"() {
        def pojo = new Pojo("Brad", 42)
        when:
        def getter = LambdaFetchingSupport.createGetter(Pojo.class, "recordLike")
        then:
        getter.isPresent()
        getter.get().apply(pojo) == "recordLike"

        //
        // pojo getters will be found first - to prevent escalation from the old way to the new record like way
        def confusedPojo = new ConfusedPojo()
        when:
        getter = LambdaFetchingSupport.createGetter(ConfusedPojo.class, "recordLike")
        then:
        getter.isPresent()
        getter.get().apply(confusedPojo) == "getRecordLike"

    }

    def "get make getters based on record like names (confusing ones)"() {
        def confusedPojo = new ConfusedPojo()

        // Record like name that just starts with `get` but not a getter.
        when:
        def getter = LambdaFetchingSupport.createGetter(ConfusedPojo.class, "gettingConfused")
        then:
        getter.isPresent()
        getter.get().apply(confusedPojo) == "gettingConfused"

        // Record like name that just starts with `is` but not a boolean getter.
        when:
        getter = LambdaFetchingSupport.createGetter(ConfusedPojo.class, "issues")
        then:
        getter.isPresent()
        getter.get().apply(confusedPojo) == "issues"
    }

    def "will handle bad methods and missing ones"() {

        when:
        def getter = LambdaFetchingSupport.createGetter(Pojo.class, "nameX")
        then:
        !getter.isPresent()

        when:
        getter = LambdaFetchingSupport.createGetter(Pojo.class, "get")
        then:
        !getter.isPresent()

        when:
        getter = LambdaFetchingSupport.createGetter(Pojo.class, "is")
        then:
        !getter.isPresent()

    }

    def "can handle boolean setters - is by preference"() {

        def pojo = new Pojo("Brad", 42)
        when:
        def getter = LambdaFetchingSupport.createGetter(Pojo.class, "interesting")
        then:
        getter.isPresent()
        getter.get().apply(pojo) == true

        when:
        getter = LambdaFetchingSupport.createGetter(Pojo.class, "alone")
        then:
        getter.isPresent()
        getter.get().apply(pojo) == true

        when:
        getter = LambdaFetchingSupport.createGetter(Pojo.class, "booleanAndNullish")
        then:
        getter.isPresent()
        getter.get().apply(pojo) == null
    }

    def "will ignore non public methods"() {

        when:
        def getter = LambdaFetchingSupport.createGetter(Pojo.class, "protectedLevelMethod")
        then:
        !getter.isPresent()

        when:
        getter = LambdaFetchingSupport.createGetter(Pojo.class, "privateLevelMethod")
        then:
        !getter.isPresent()

        when:
        getter = LambdaFetchingSupport.createGetter(Pojo.class, "packageLevelMethod")
        then:
        !getter.isPresent()
    }

    def "can proxy Kotlin-from-Java Pojo methods"() {

        def pojo = new KotlinPojo("Brad", 42, true, true, 0)
        when:
        def getName = LambdaFetchingSupport.mkCallFunction(KotlinPojo.class, "getName", String.class)
        def getAge = LambdaFetchingSupport.mkCallFunction(KotlinPojo.class, "getAge", Integer.TYPE)
        def getCanCode = LambdaFetchingSupport.mkCallFunction(KotlinPojo.class, "getCanCode", Boolean.TYPE)
        def getIsAwesome = LambdaFetchingSupport.mkCallFunction(KotlinPojo.class, "isAwesome", Boolean.TYPE)
        def getIsBoolMimic = LambdaFetchingSupport.mkCallFunction(KotlinPojo.class, "isBoolMimic", Integer.TYPE)

        then:
        getName.apply(pojo) == "Brad"
        getAge.apply(pojo) == 42
        getCanCode.apply(pojo) == true
        getIsAwesome.apply(pojo) == true
        getIsBoolMimic.apply(pojo) == 0
    }

    def "can handle Kotlin properties"() {
        def pojo = new KotlinPojo("Brad", 42, true, true, 0)

        when:
        def getter = LambdaFetchingSupport.createGetter(KotlinPojo.class, "name")
        then:
        getter.isPresent()
        getter.get().apply(pojo) == "Brad"

        when:
        getter = LambdaFetchingSupport.createGetter(KotlinPojo.class, "age")
        then:
        getter.isPresent()
        getter.get().apply(pojo) == 42

        when:
        getter = LambdaFetchingSupport.createGetter(KotlinPojo.class, "canCode")
        then:
        getter.isPresent()
        getter.get().apply(pojo) == true

        when:
        getter = LambdaFetchingSupport.createGetter(KotlinPojo.class, "isAwesome")
        then:
        getter.isPresent()
        getter.get().apply(pojo) == true

        when:
        getter = LambdaFetchingSupport.createGetter(KotlinPojo.class, "isBoolMimic")
        then:
        getter.isPresent()
        getter.get().apply(pojo) == 0
    }
}
