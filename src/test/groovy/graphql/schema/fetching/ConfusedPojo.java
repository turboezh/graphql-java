package graphql.schema.fetching;

public class ConfusedPojo {

    public String getRecordLike() {
        return "getRecordLike";
    }

    public String recordLike() {
        return "recordLike";
    }

    /**
     * It just starts with `get` but not a getter.
     */
    public String gettingConfused() {
        return "gettingConfused";
    }

    /**
     * It just starts with `is` but not a boolean getter.
     */
    public String issues() {
        return "issues";
    }
}
