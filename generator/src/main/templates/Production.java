package ${packageName};

import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import lombok.NonNull;

@Slf4j
@ToString
@EqualsAndHashCode
public class ${productionClassName} {
    private final String property;

    public ${productionClassName}(@NonNull String param) {
        log.debug("Initialized ${productionClassName} with property='{}'", param);
        this.property = param;
    }

    public String getProperty() {
        return property;
    }
<% propertyCount.times { %>
    @Getter @Setter private String prop${it};
<% } %>
}
