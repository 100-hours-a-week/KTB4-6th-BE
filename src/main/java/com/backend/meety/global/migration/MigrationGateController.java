package com.backend.meety.global.migration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/db-migration/gate")
@ConditionalOnProperty(name = "migration.write-gate.enabled", havingValue = "true")
public class MigrationGateController {
    private final MigrationWriteGate gate;

    public MigrationGateController(MigrationWriteGate gate) {
        this.gate = gate;
    }


    @GetMapping
    public MigrationWriteGate.Status status() {
        return gate.status();
    }


    @PutMapping("/closed")
    public MigrationWriteGate.Status close() {
        return gate.close();
    }


    @PutMapping("/open")
    public MigrationWriteGate.Status open() {
        return gate.open();
    }
}
