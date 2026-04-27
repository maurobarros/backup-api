package com.example.backup_api;

import org.springframework.data.jpa.repository.JpaRepository;

//Cria todos os metodos de acesso a BD (save, finAllm findById...)
public interface BackupRepository extends JpaRepository<BackupRecord, Long> {

}
