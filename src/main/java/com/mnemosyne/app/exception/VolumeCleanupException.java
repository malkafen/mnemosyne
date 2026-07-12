package com.mnemosyne.app.exception;

public class VolumeCleanupException extends RuntimeException {

  public VolumeCleanupException(String message, Throwable cause) {
    super(message, cause);
  }

  public VolumeCleanupException(String message) {
    super(message);
  }
}
