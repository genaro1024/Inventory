package com.store.inventory.web;

final class ApiErrorMessages {

    private ApiErrorMessages() {
    }

    static String forStatus(int status) {
        return switch (status) {
            case 400 -> "Revisa los datos de tu solicitud e inténtalo nuevamente.";
            case 404 -> "No encontramos el recurso que solicitaste.";
            case 405 -> "Esta operación no está disponible para esa ruta.";
            case 406 -> "La respuesta se ofrece en formato JSON.";
            case 413 -> "La solicitud es demasiado grande.";
            case 415 -> "Envía la solicitud en formato JSON.";
            default -> status >= 500 ? "No pudimos completar tu solicitud. Inténtalo nuevamente."
                    : "No pudimos procesar tu solicitud.";
        };
    }
}
