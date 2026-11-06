package com.hgtv.clean;

import java.io.IOException;
import java.util.Arrays;

/** The protocol's small substitution/permutation block transform for the signature envelope. */
final class SignatureCipher {
    private final int[] substitutions;
    private final int[] rows;
    private final int[] positions;
    private final int[][] keys = new int[12][4];

    SignatureCipher(int timestamp) {
        int variant = timestamp & 3;
        substitutions = new int[256];
        for (int i = 0; i < 256; i++) substitutions[i] = ProtocolTables.SUBSTITUTIONS[variant * 256 + i] & 255;
        rows = new int[][]{{1,0,2,3}, {2,0,3,1}, {0,1,3,2}, {1,0,2,3}}[variant];
        positions = new int[][]{{0,9,14,11,4,13,2,7,8,1,6,15,12,5,10,3}, {0,9,14,15,4,13,2,7,8,1,6,3,12,5,10,11},
                {0,9,14,7,4,13,2,11,8,1,6,3,12,5,10,15}, {0,9,14,11,4,13,2,7,8,1,6,15,12,5,10,3}}[variant];
        int seed = new int[]{-905814564,-2109881018,-918420093,-1030217121}[variant];
        byte[] master = RequestSigner.unhex("f1593376766ea98d34f31b057a9d5be4");
        int[] expanded = new int[48];
        for (int i = 0; i < 16; i++) expanded[i] = ((seed >> ((i & 3) * 8)) & 255) ^ (master[i] & 255);
        int rotate = 8;
        for (int word = 4; word < 12; word++) {
            int offset = (word - 1) * 4;
            int a=expanded[offset],b=expanded[offset+1],c=expanded[offset+2],d=expanded[offset+3];
            if ((word & 3) == 0) { int next=(substitutions[b] ^ (seed >> (rotate & 24))) & 255; b=substitutions[c];c=substitutions[d];d=substitutions[a];a=next; }
            rotate += 2;
            expanded[offset+4] = a ^ expanded[offset-12]; expanded[offset+5] = b ^ expanded[offset-11];
            expanded[offset+6] = c ^ expanded[offset-10]; expanded[offset+7] = d ^ expanded[offset-9];
        }
        for (int row=0;row<12;row++) System.arraycopy(expanded,row*4,keys[row],0,4);
    }

    byte[] encrypt(byte[] input, byte[] iv) throws IOException {
        if (input.length < 248 || iv.length != 16) throw new IOException("签名数据长度无效");
        byte[] selected = new byte[32];
        for (int i=0;i<31;i++) {
            int at=i*8;
            selected[i]=(byte) (((input[at+7]&255)>>4&8) | ((input[at+5]&255)<<3&16) | ((input[at+4]&255)>>1&4)
                    | ((input[at]&255)>>4&2) | (input[at+1]&64) | ((input[at+2]&255)>>2&1) | ((input[at+3]&255)<<3&128) | ((input[at+6]&255)<<5&32));
        }
        selected[31]=1;
        byte[] first=new byte[16];for(int i=0;i<16;i++)first[i]=(byte)(selected[i]^iv[i]);first=block(first);
        byte[] second=new byte[16];for(int i=0;i<16;i++)second[i]=(byte)(selected[i+16]^first[i]);second=block(second);
        byte[] encrypted=RequestSigner.concat(first,second), result=Arrays.copyOf(input,input.length);
        for(int i=0;i<31;i++) {
            int at=i*8,value=encrypted[i]&255;
            result[at]=(byte)((result[at]&223)|((value<<4)&32));result[at+1]=(byte)((result[at+1]&191)|(value&64));
            result[at+2]=(byte)((result[at+2]&251)|((value<<2)&4));result[at+3]=(byte)((result[at+3]&239)|((value>>3)&16));
            result[at+4]=(byte)((result[at+4]&247)|((value<<1)&8));result[at+5]=(byte)((result[at+5]&253)|((value>>3)&2));
            result[at+6]=(byte)((result[at+6]&254)|((value>>5)&1));result[at+7]=(byte)((result[at+7]&127)|((value<<4)&128));
        }
        return RequestSigner.concat(new byte[]{encrypted[31]},result);
    }

    byte[] block(byte[] input) {
        int[][] state=new int[4][4];for(int i=0;i<16;i++)state[i/4][i%4]=input[i]&255;
        addKey(state,0);
        for(int round=1;round<=2;round++) {
            for(int row=0;row<4;row++)for(int column=0;column<4;column++)state[row][column]=substitutions[state[row][column]];
            int[][] reordered=new int[4][4];for(int row=0;row<4;row++)reordered[row]=state[rows[row]].clone();
            int[] flat=new int[16];for(int row=0;row<4;row++)System.arraycopy(reordered[row],0,flat,row*4,4);
            for(int i=0;i<16;i++)state[i/4][i%4]=flat[positions[i]];
            if(round==1) {
                for(int row=0;row<4;row++) { int[] copy=state[row].clone();for(int column=0;column<4;column++)state[row][column]=copy[rows[column]]; }
                for(int column=0;column<4;column++) {
                    int a=state[0][column],b=state[1][column],c=state[2][column],d=state[3][column],sum=a^b^c^d;
                    state[0][column]=doubleByte(a^b)^a^sum;state[1][column]=doubleByte(b^c)^b^sum;
                    state[2][column]=doubleByte(c^d)^c^sum;state[3][column]=doubleByte(a^d)^d^sum;
                }
            }
            addKey(state,round*4);
        }
        byte[] output=new byte[16];for(int i=0;i<16;i++)output[i]=(byte)(state[i/4][i%4]^keys[i/4+4][i%4]);return output;
    }

    private void addKey(int[][] state,int start) {for(int row=0;row<4;row++)for(int column=0;column<4;column++)state[row][column]^=keys[start+row][rows[column]];}
    private static int doubleByte(int value) {return ((value<<1)^((value&128)!=0?27:0))&255;}
}
