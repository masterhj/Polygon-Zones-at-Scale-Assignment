package com.polyzones.service.web;

import java.io.IOException;

public final class PayloadTooLargeException extends IOException {
    private static final long serialVersionUID = 1L;
    public PayloadTooLargeException() { super("Request body limit exceeded"); }
}
