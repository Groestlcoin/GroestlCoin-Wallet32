/*
 * Copyright 2013 Ken Sedgwick
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.bitcoin.crypto;

import com.google.bitcoin.core.Sha256Hash;
import org.spongycastle.util.encoders.Hex;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A MnemonicCode object may be used to convert between binary seed values and
 * lists of words per <a href="https://en.bitcoin.it/wiki/BIP_0039">the BIP 39
 * specification</a>
 */

public class MnemonicCode {
    private ArrayList<String> wordList;

    public static String BIP39_ENGLISH_SHA256 = "ad90bf3beb7b0eb7e5acd74727dc0da96e0a280a258354e7293fb7e211ac03db";

    private static final int HMAC_ROUNDS = 10000;

    public MnemonicCode() throws IOException {
        this(MnemonicCode.class.getResourceAsStream("mnemonic/wordlist/english.txt"), BIP39_ENGLISH_SHA256);
    }

    /**
     * Creates an MnemonicCode object, initializing with words read from the supplied input stream.  If a wordListDigest
     * is supplied the digest of the words will be checked.
     */
    public MnemonicCode(InputStream wordstream, String wordListDigest) throws IOException, IllegalArgumentException {
        BufferedReader br = new BufferedReader(new InputStreamReader(wordstream, "UTF-8"));
        String word;
        this.wordList = new ArrayList<String>();
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new RuntimeException(ex);		// Can't happen.
        }
        while ((word = br.readLine()) != null) {
            md.update(word.getBytes());
            this.wordList.add(word);
        }
        br.close();

        if (this.wordList.size() != 2048)
            throw new IllegalArgumentException("input stream did not contain 2048 words");

        // If a wordListDigest is supplied check to make sure it matches.
        if (wordListDigest != null) {
            byte[] digest = md.digest();
            String hexdigest = new String(Hex.encode(digest));
            if (!hexdigest.equals(wordListDigest))
                throw new IllegalArgumentException("wordlist digest mismatch");
        }
    }

    /**
     * Convert mnemonic word list to seed.
     */
    public static byte[] toSeed(List<String> words, String passphrase) {

        // To create binary seed from mnemonic, we use HMAC-SHA512
        // function with string "mnemonic" + passphrase (in UTF-8) as
        // key and mnemonic sentence (again in UTF-8) as the
        // message. We perform 10000 HMAC rounds and use the final
        // result as the binary seed.
        //
        // Pseudocode:
        //
        // K = "mnemonic" + passphrase
        // M = mnemonic_sentence
        // for i in 1 ... 10000 do
        //     M = hmac_sha512(K, M)
        // done
        // seed = M

        byte[] kk = new String("mnemonic" + passphrase).getBytes();
        byte[] mm = joinStringList(words).getBytes();

        for (int ii = 0; ii < HMAC_ROUNDS; ++ii)
            mm = HDUtils2.hmacSha512(kk, mm);

        return mm;
    }

    /**
     * Convert entropy data to mnemonic word list.
     */
    public List<String> toMnemonic(byte[] entropy) throws MnemonicLengthException {
        if (entropy.length % 4 > 0)
            throw new MnemonicLengthException("entropy length not multiple of 32 bits");

        // We take initial entropy of ENT bits and compute its
        // checksum by taking first ENT / 32 bits of its SHA256 hash.

        byte[] hash = Sha256Hash.create(entropy).getBytes();
        boolean[] hashBits = bytesToBits(hash);
        
        boolean[] entropyBits = bytesToBits(entropy);
        int checksumLengthBits = entropyBits.length / 32;

        // We append these bits to the end of the initial entropy. 
        boolean[] concatBits = new boolean[entropyBits.length + checksumLengthBits];
        for (int ii = 0; ii < entropyBits.length; ++ii)
            concatBits[ii] = entropyBits[ii];
        for (int ii = 0; ii < checksumLengthBits; ++ii)
            concatBits[entropyBits.length + ii] = hashBits[ii];

        // Next we take these concatenated bits and split them into
        // groups of 11 bits. Each group encodes number from 0-2047
        // which is a position in a wordlist.  We convert numbers into
        // words and use joined words as mnemonic sentence.

        ArrayList<String> words = new ArrayList<String>();
        int nwords = concatBits.length / 11;
        for (int ii = 0; ii < nwords; ++ii) {
            int ndx = 0;
            for (int jj = 0; jj < 11; ++jj) {
                ndx <<= 1;
                if (concatBits[(ii * 11) + jj])
                    ndx |= 0x1;
            }
            words.add(this.wordList.get(ndx));
        }
            
        return words;        
    }

    /**
     * Check to see if a mnemonic word list is valid.
     */
    public void check(List<String> words) throws MnemonicLengthException, MnemonicWordException, MnemonicChecksumException {
        if (words.size() % 3 > 0)
            throw new MnemonicLengthException("Word list size must be multiple of three words.");

        // Look up all the words in the list and construct the
        // concatenation of the original entropy and the checksum.
        //
        int concatLenBits = words.size() * 11;
        boolean[] concatBits = new boolean[concatLenBits];
        int wordindex = 0;
        for (String word : words) {
            // Find the words index in the wordlist.
            int ndx = Collections.binarySearch(this.wordList, word);
            if (ndx < 0)
                throw new MnemonicWordException("\"" + word + "\" invalid", word);

            // Set the next 11 bits to the value of the index.
            for (int ii = 0; ii < 11; ++ii)
                concatBits[(wordindex * 11) + ii] = (ndx & (1 << (10 - ii))) != 0;
            ++wordindex;
        }        

        int checksumLengthBits = concatLenBits / 33;
        int entropyLengthBits = concatLenBits - checksumLengthBits;

        // Extract original entropy as bytes.
        byte[] entropy = new byte[entropyLengthBits / 8];
        for (int ii = 0; ii < entropy.length; ++ii)
            for (int jj = 0; jj < 8; ++jj)
                if (concatBits[(ii * 8) + jj])
                    entropy[ii] |= 1 << (7 - jj);

        // Take the digest of the entropy.
        byte[] hash = Sha256Hash.create(entropy).getBytes();
        boolean[] hashBits = bytesToBits(hash);

        // Check all the checksum bits.
        for (int ii = 0; ii < checksumLengthBits; ++ii)
            if (concatBits[entropyLengthBits + ii] != hashBits[ii])
                throw new MnemonicChecksumException("checksum error");
    }

    private static boolean[] bytesToBits(byte[] data) {
        boolean[] bits = new boolean[data.length * 8];
        for (int ii = 0; ii < data.length; ++ii)
            for (int jj = 0; jj < 8; ++jj)
                bits[(ii * 8) + jj] = (data[ii] & (1 << (7 - jj))) != 0;
        return bits;
    }

    static private String joinStringList(List<String> list) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String item : list)
        {
            if (first)
                first = false;
            else
                sb.append(" ");
            sb.append(item);
        }
        return sb.toString();
    }
}
