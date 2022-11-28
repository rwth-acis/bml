package i5.bml.parser.types.openapi;

import i5.bml.parser.types.AbstractBMLType;
import i5.bml.parser.types.BMLType;
import org.antlr.symtab.Type;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.Map;

@BMLType(name = "OpenAPISchema", isComplex = true)
public class BMLOpenAPISchema extends AbstractBMLType {

    // TODO: Track required attributes/properties/fields

    private String schemaName;

    public BMLOpenAPISchema() {}

    public BMLOpenAPISchema(String schemaName, Map<String, Type> supportedAccesses) {
        this.schemaName = schemaName;
        this.supportedAccesses = supportedAccesses;
    }

    @Override
    public String getName() {
        return schemaName;
    }

    @Override
    public Type resolveAccess(ParseTree ctx) {
        return supportedAccesses.get(ctx.getText());
    }
}
