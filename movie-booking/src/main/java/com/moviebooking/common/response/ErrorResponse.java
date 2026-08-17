package com.moviebooking.common.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class ErrorResponse {

    private boolean success;

    private String message;

    private List<String> errors;
}