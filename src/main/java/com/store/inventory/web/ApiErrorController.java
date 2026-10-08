package com.store.inventory.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiErrorController implements ErrorController {

    private static final Logger LOG = LoggerFactory.getLogger(ApiErrorController.class);

    @RequestMapping("${server.error.path:/error}")
    public ResponseEntity<ApiResponse<Void>> error(HttpServletRequest request) {
        var attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = attribute instanceof Integer value && value >= 400 && value <= 599 ? value : 404;
        if (status >= 500 && request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable failure) {
            LOG.error("Unhandled servlet failure", failure);
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(ApiErrorMessages.forStatus(status)));
    }
}
