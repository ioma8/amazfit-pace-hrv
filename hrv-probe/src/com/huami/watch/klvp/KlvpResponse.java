package com.huami.watch.klvp;

import android.os.Parcel;
import android.os.Parcelable;

public class KlvpResponse implements Parcelable {
    public static final Parcelable.Creator<KlvpResponse> CREATOR = new Parcelable.Creator<KlvpResponse>() {
        @Override
        public KlvpResponse createFromParcel(Parcel source) {
            return new KlvpResponse(source);
        }

        @Override
        public KlvpResponse[] newArray(int size) {
            return new KlvpResponse[size];
        }
    };
    public static final short MAX_VALUES = 1000;
    private static final String TAG = "hm_KlvpResponse";
    public byte cmd;
    public byte msgRemain;
    public short pairId;
    public byte responseCode;
    public byte[] responseValues;
    public short target;

    public KlvpResponse(Parcel source) {
        readFromParcel(source);
    }

    public KlvpResponse() {
    }

    private void readFromParcel(Parcel source) {
        this.pairId = (short) source.readInt();
        this.msgRemain = source.readByte();
        this.cmd = source.readByte();
        this.responseCode = source.readByte();
        this.target = (short) source.readInt();
        int len = source.readInt();
        if (len < 0) {
            this.responseValues = null;
        } else {
            this.responseValues = new byte[len];
            source.readByteArray(this.responseValues);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(this.pairId);
        dest.writeByte(this.msgRemain);
        dest.writeByte(this.cmd);
        dest.writeByte(this.responseCode);
        dest.writeInt(this.target);
        if (this.responseValues == null) {
            dest.writeInt(-1);
            return;
        }
        int len = this.responseValues.length;
        dest.writeInt(len);
        dest.writeByteArray(this.responseValues);
    }

    @Override
    public String toString() {
        String request = "{ pairId = " + ((int) this.pairId) + " msgRemain = " + ((int) this.msgRemain) + " cmd = " + ((int) this.cmd) + " responseCode = " + ((int) this.responseCode) + " target = " + ((int) this.target);
        if (this.responseValues != null) {
            StringBuilder v = new StringBuilder();
            for (byte b : this.responseValues) v.append(String.format("%02x", b));
            return request + " values = " + v + " }";
        }
        return request + " values = null }";
    }
}
