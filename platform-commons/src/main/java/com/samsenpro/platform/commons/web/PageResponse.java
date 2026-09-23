package com.samsenpro.platform.commons.web;

import java.util.List;

/** Página de resultados con un formato JSON estable (no expone la estructura interna de Spring Data). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
}
