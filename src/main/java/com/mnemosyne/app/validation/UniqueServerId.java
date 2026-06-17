package com.mnemosyne.app.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = UniqueServerIdValidator.class)
public @interface UniqueServerId {
  String message() default "Server id must be unique within a group";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
