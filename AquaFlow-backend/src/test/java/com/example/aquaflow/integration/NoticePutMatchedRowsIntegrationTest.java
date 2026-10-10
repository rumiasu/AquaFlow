package com.example.aquaflow.integration;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "notice-test.changed-rows=false")
class NoticePutMatchedRowsIntegrationTest extends NoticePutIntegrationSupport {}
