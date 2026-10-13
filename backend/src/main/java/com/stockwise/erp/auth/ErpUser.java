package com.stockwise.erp.auth;

public record ErpUser(long id, long organizationId, String fullName, String email, String role) { }
