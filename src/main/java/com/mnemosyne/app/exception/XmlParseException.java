package com.mnemosyne.app.exception;

public class XmlParseException extends RuntimeException {

  public XmlParseException(String message, Throwable cause) {
    super(message, cause);
  }

  public XmlParseException(String message) {
    super(message);
  }
}
