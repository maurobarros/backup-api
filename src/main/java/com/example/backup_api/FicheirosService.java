package com.example.backup_api;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.stereotype.Service;
import com.google.cloud.NoCredentials;

import java.util.ArrayList;
import java.util.List;

@Service
public class FicheirosService {

    private final BackupRepository backupRepository;
    private final Storage storage;
    private static final String BUCKET_NAME = "backup-mauro";

   /* public FicheirosService(BackupRepository backupRepository) {
        this.backupRepository = backupRepository;
        this.storage = StorageOptions.getDefaultInstance().getService();
    }*/

    public FicheirosService(BackupRepository backupRepository) {
        this.backupRepository = backupRepository;

        String emulatorHost = System.getenv("STORAGE_EMULATOR_HOST");
        if (emulatorHost != null && !emulatorHost.isBlank()) {
            this.storage = StorageOptions.newBuilder()
                    .setHost(emulatorHost)
                    .setProjectId("test-project")
                    .setCredentials(NoCredentials.getInstance())
                    .build()
                    .getService();
        } else {
            this.storage = StorageOptions.getDefaultInstance().getService();
        }
    }

    public List<String> giveListOfBackups() {
        List<String> listOfBackups = new ArrayList<>();

        Iterable<Blob> blobs = storage.list(BUCKET_NAME).iterateAll();

        for (Blob blob : blobs) {
            String fileName = blob.getName();
            listOfBackups.add(fileName);

            boolean alreadyExist = backupRepository.findAll()
                    .stream()
                    .anyMatch(x -> x.getNomeFicheiro().equals(fileName));
            if (!alreadyExist) {
                backupRepository.save(new BackupRecord(fileName));
            }
        }

        return listOfBackups;
    }

    public List<BackupRecord> getHistorico() {
        return backupRepository.findAll();
    }

    public byte[] downloadFicheiro(String nome) {
        Blob blob = storage.get(BUCKET_NAME, nome);
        if (blob == null) {
            return null;
        }
        return blob.getContent();
    }
}