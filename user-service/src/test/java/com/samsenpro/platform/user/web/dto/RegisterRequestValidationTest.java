package com.samsenpro.platform.user.web.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest(name = "{0} / {1} -> invalid: [{3}]")
    @CsvSource(delimiter = '|', value = {
            "alice        | alice@example.com | S3cure-Passw0rd | ",
            "al           | alice@example.com | S3cure-Passw0rd | username",
            "alice smith  | alice@example.com | S3cure-Passw0rd | username",
            "<script>     | alice@example.com | S3cure-Passw0rd | username",
            "alice        | not-an-email      | S3cure-Passw0rd | email",
            "alice        | alice@example.com | short           | password",
    })
    void validatesEachField(String username, String email, String password, String invalidField) {
        Set<String> invalid = validator.validate(new RegisterRequest(username, email, password)).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());

        if (invalidField == null) {
            assertThat(invalid).isEmpty();
        } else {
            assertThat(invalid).containsExactly(invalidField);
        }
    }
}
