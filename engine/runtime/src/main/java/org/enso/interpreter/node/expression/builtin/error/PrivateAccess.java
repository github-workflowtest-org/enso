package org.enso.interpreter.node.expression.builtin.error;

import java.util.List;
import org.enso.interpreter.dsl.BuiltinType;
import org.enso.interpreter.node.expression.builtin.UniquelyConstructibleBuiltin;

@BuiltinType
public class PrivateAccess extends UniquelyConstructibleBuiltin {

  @Override
  protected String getConstructorName() {
    return "Error";
  }

  @Override
  protected List<String> getConstructorParamNames() {
    return List.of("this_project_name", "target_project_name", "target_method_name");
  }
}
