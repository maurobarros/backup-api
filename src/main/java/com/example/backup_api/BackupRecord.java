package com.example.backup_api;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class BackupRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nomeFicheiro;
    private LocalDateTime dataCriacao;

    // Construtor vazio - obrigatório para o JPA
    public BackupRecord() {}

    // Construtor que usamos no Service
    public BackupRecord(String nomeFicheiro) {
        this.nomeFicheiro = nomeFicheiro;
        this.dataCriacao = LocalDateTime.now();
    }

    // Getters
    public Long getId() { return id; }
    public String getNomeFicheiro() { return nomeFicheiro; }
    public LocalDateTime getDataCriacao() { return dataCriacao; }
}