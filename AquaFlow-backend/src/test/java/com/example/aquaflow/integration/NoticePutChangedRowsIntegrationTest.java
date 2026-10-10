package com.example.aquaflow.integration;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "notice-test.changed-rows=true")
class NoticePutChangedRowsIntegrationTest extends NoticePutIntegrationSupport {}
