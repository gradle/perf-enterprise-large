package ${packageName};

import static org.junit.Assert.*;

public class ${testClassName} {
    private final ${productionClassName} production = new ${productionClassName}("value");

<% testMethodCount.times { %>
    @org.junit.Test
    public void test${it}() {
        assertEquals(production.getProperty(), "value");
    }
<% } %>
}