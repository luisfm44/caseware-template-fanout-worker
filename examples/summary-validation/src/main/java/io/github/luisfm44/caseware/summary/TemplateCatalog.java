package io.github.luisfm44.caseware.summary;

import java.util.Optional;

interface TemplateCatalog {

    Optional<Artifact> approved(String revision);

    record Artifact(String revision, String approvedSha256, byte[] bytes) {

        public Artifact {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
