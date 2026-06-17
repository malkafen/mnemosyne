package com.mnemosyne.app.validation;

import com.mnemosyne.app.model.Mnemon;
import com.mnemosyne.app.model.Server;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

public class UniqueServerIdValidator implements ConstraintValidator<UniqueServerId, Mnemon> {

  @Override
  public boolean isValid(Mnemon m, ConstraintValidatorContext ctx) {
    if (m == null || m.getServers() == null) {
      return true; // null ловят @NotNull/@Size на servers
    }
    Set<String> seen = new HashSet<>();
    Set<String> dups = new LinkedHashSet<>();
    for (Server s : m.getServers()) {
      if (!seen.add(s.getId())) {
        dups.add(s.getId());
      }
    }
    if (dups.isEmpty()) return true;

    ctx.disableDefaultConstraintViolation();
    ctx.buildConstraintViolationWithTemplate("Duplicate server id(s) within group: " + dups)
        .addPropertyNode("servers")
        .addConstraintViolation();
    return false;
  }
}
