package com.github.liblevenshtein;

import org.apache.commons.lang3.StringEscapeUtils;

import com.github.liblevenshtein.transducer.Candidate;

/**
 * Prints the distance between a query term and spelling candidate, without
 * syntax highlighting.
 */
public class CandidatePrinter extends AbstractPrinter {

  /**
   * {@inheritDoc}
   */
  @Override
  public void print(
      final StringBuilder buffer,
      final String escapedQuery,
      final Object object) {
    buffer.setLength(0);
    final Candidate spellingCandidate = (Candidate) object;
    final String escapedCandidate =
      StringEscapeUtils.escapeJava(spellingCandidate.term());
      // don't print the distance 0 ones
    if (spellingCandidate.distance() > 0) {

      // rather than print out both a,b and b,a
      // just do if in lexicographic order
      // a.compareTo(b) returns -1 if a < b
      if (escapedQuery.compareTo(escapedCandidate) < 0) {
        buffer.append("*\t").append(escapedQuery)
            .append('\t')
            .append(escapedCandidate)
            .append('\t')
            .append(spellingCandidate.distance());
        System.out.println(buffer.toString());              
      }
    }
  }
}
