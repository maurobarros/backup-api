package com.example.backup_api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/ficheiros")
public class FicheirosController {

    private final FicheirosService ficheirosService;

    public FicheirosController(FicheirosService ficheirosService) {
        this.ficheirosService = ficheirosService;
    }

    @GetMapping
    public List<String> listar() {
        return ficheirosService.giveListOfBackups();
    }

    @GetMapping("/{nome}")
    public ResponseEntity<byte[]> download(@PathVariable String nome) {
        if (nome.contains("..") || nome.contains("/")) {
            return ResponseEntity.badRequest().build();
        }

        byte[] content = ficheirosService.downloadFicheiro(nome);

        if (content == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + nome)
                .body(content);
    }

    @GetMapping("/historico")
    public List<BackupRecord> historico() {
        return ficheirosService.getHistorico();
    }
}