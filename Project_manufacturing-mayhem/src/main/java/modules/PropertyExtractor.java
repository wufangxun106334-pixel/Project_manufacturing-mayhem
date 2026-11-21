package modules;

import java.util.Collection;

import astra.core.Module;
import astra.formula.Formula;
import astra.formula.Predicate;
import astra.term.ListTerm;
import astra.term.Primitive;
import cartago.ArtifactObsProperty;

public class PropertyExtractor extends Module {
    @SuppressWarnings("unchecked")
    @TERM
    public ListTerm listValues(ArtifactObsProperty property) {
        ListTerm list = new ListTerm();
        for (String value : (Collection<String>) property.getValue()) {
            list.add(Primitive.newPrimitive(value));
        };

        return list;
    }

    @TERM
    public String stringValue(ArtifactObsProperty property) {
        return property.stringValue();
    }

    @TERM
    public int intValue(ArtifactObsProperty property) {
        return property.intValue();
    }

    /** The value at position index of a property with several values, e.g. nextOrder(id, item, quantity). */
    @TERM
    public String stringValue(ArtifactObsProperty property, int index) {
        return property.stringValue(index);
    }

    @TERM
    public int intValue(ArtifactObsProperty property, int index) {
        return property.intValue(index);
    }
    
    @FORMULA
    public Formula isBound(String value) {
        return value == null ? Predicate.FALSE:Predicate.TRUE;
    }

    @FORMULA
    public Formula isBound(Integer value) {
        return value == null ? Predicate.FALSE:Predicate.TRUE;
    }
}
