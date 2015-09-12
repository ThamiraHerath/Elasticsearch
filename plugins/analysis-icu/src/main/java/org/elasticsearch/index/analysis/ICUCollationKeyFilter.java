package org.elasticsearch.index.analysis;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.ibm.icu.text.Collator;
import com.ibm.icu.text.RawCollationKey;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.IOException;

/**
 * <p>
 *   Converts each token into its {@link com.ibm.icu.text.CollationKey}, and
 *   then encodes the CollationKey with {@link IndexableBinaryStringTools}, to
 *   allow it to be stored as an index term.
 * </p>
 * <p>
 *   <strong>WARNING:</strong> Make sure you use exactly the same Collator at
 *   index and query time -- CollationKeys are only comparable when produced by
 *   the same Collator.  {@link com.ibm.icu.text.RuleBasedCollator}s are 
 *   independently versioned, so it is safe to search against stored
 *   CollationKeys if the following are exactly the same (best practice is
 *   to store this information with the index and check that they remain the
 *   same at query time):
 * </p>
 * <ol>
 *   <li>
 *     Collator version - see {@link Collator#getVersion()}
 *   </li>
 *   <li>
 *     The collation strength used - see {@link Collator#setStrength(int)}
 *   </li>
 * </ol> 
 * <p>
 *   CollationKeys generated by ICU Collators are not compatible with those
 *   generated by java.text.Collators.  Specifically, if you use 
 *   ICUCollationKeyFilter to generate index terms, do not use 
 *   {@code CollationKeyFilter} on the query side, or vice versa.
 * </p>
 * <p>
 *   ICUCollationKeyFilter is significantly faster and generates significantly
 *   shorter keys than CollationKeyFilter.  See
 *   <a href="http://site.icu-project.org/charts/collation-icu4j-sun"
 *   >http://site.icu-project.org/charts/collation-icu4j-sun</a> for key
 *   generation timing and key length comparisons between ICU4J and
 *   java.text.Collator over several languages.
 * </p>
 *  @deprecated Use {@link ICUCollationAttributeFactory} instead, which encodes
 *  terms directly as bytes. This filter WAS removed in Lucene 5.0
 */
@Deprecated
public final class ICUCollationKeyFilter extends TokenFilter {
  private Collator collator = null;
  private RawCollationKey reusableKey = new RawCollationKey();
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);

  /**
   * 
   * @param input Source token stream
   * @param collator CollationKey generator
   */
  public ICUCollationKeyFilter(TokenStream input, Collator collator) {
    super(input);
    // clone the collator: see http://userguide.icu-project.org/collation/architecture
    try {
      this.collator = (Collator) collator.clone();
    } catch (CloneNotSupportedException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public boolean incrementToken() throws IOException {
    if (input.incrementToken()) {
      char[] termBuffer = termAtt.buffer();
      String termText = new String(termBuffer, 0, termAtt.length());
      collator.getRawCollationKey(termText, reusableKey);
      int encodedLength = IndexableBinaryStringTools.getEncodedLength(
          reusableKey.bytes, 0, reusableKey.size);
      if (encodedLength > termBuffer.length) {
        termAtt.resizeBuffer(encodedLength);
      }
      termAtt.setLength(encodedLength);
      IndexableBinaryStringTools.encode(reusableKey.bytes, 0, reusableKey.size,
          termAtt.buffer(), 0, encodedLength);
      return true;
    } else {
      return false;
    }
  }
}
