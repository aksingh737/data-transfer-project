package org.datatransferproject.types.common;

import com.google.common.base.Throwables;

/** Utility class for handling exceptions. */
public final class ExceptionUtils {

  private ExceptionUtils() {}

  /**
   * Formats a Throwable's stack trace using Guava's Throwables.getStackTraceAsString,
   * but collapses consecutive identical lines to prevent log bloat.
   * This inherits correct cause and suppressed exception handling from the JDK.
   */
  public static String getStackTraceAsString(Throwable t) {
    if (t == null) {
      return "";
    }
    String fullTrace = Throwables.getStackTraceAsString(t);
    String[] lines = fullTrace.split("\n");
    if (lines.length == 0) {
      return fullTrace;
    }

    StringBuilder sb = new StringBuilder();
    String lastLine = null;
    int duplicateCount = 0;

    for (String rawLine : lines) {
      String line = rawLine;
      if (line.endsWith("\r")) {
        line = line.substring(0, line.length() - 1);
      }
      
      if (lastLine != null && line.equals(lastLine)) {
        duplicateCount++;
      } else {
        if (duplicateCount > 0) {
          sb.append("\t... repeated ").append(duplicateCount).append(" times more\n");
          duplicateCount = 0;
        }
        sb.append(line).append('\n');
        lastLine = line;
      }
    }
    
    if (duplicateCount > 0) {
      sb.append("\t... repeated ").append(duplicateCount).append(" times more\n");
    }
    
    // Remove the trailing newline introduced by the post-processing
    // if the original String didn't end with a newline line separator.
    if (sb.length() > 0 && !fullTrace.endsWith("\n") && sb.charAt(sb.length() - 1) == '\n') {
       sb.setLength(sb.length() - 1);
    }
    return sb.toString();
  }
}
