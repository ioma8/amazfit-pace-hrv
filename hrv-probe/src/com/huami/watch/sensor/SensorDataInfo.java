package com.huami.watch.sensor;

import android.os.Parcel;
import android.os.Parcelable;

public class SensorDataInfo implements Parcelable {
    public static final Parcelable.Creator<SensorDataInfo> CREATOR = new Parcelable.Creator<SensorDataInfo>() {
        @Override
        public SensorDataInfo createFromParcel(Parcel in) {
            return new SensorDataInfo(in);
        }

        @Override
        public SensorDataInfo[] newArray(int size) {
            return new SensorDataInfo[size];
        }
    };
    int mFloorCount;
    int mHeartRate;
    int mQuality;
    float mSportCalories;
    int mU4HeartRate;
    int mU4Step;
    int mU4Vercode;

    public SensorDataInfo() {
    }

    public SensorDataInfo(Parcel in) {
        readFromParcel(in);
    }

    private void readFromParcel(Parcel in) {
        this.mU4Vercode = in.readInt();
        this.mU4Step = in.readInt();
        this.mU4HeartRate = in.readInt();
        this.mSportCalories = in.readFloat();
        this.mHeartRate = in.readInt();
        this.mQuality = in.readInt();
        this.mFloorCount = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(this.mU4Vercode);
        dest.writeInt(this.mU4Step);
        dest.writeInt(this.mU4HeartRate);
        dest.writeFloat(this.mSportCalories);
        dest.writeInt(this.mHeartRate);
        dest.writeInt(this.mQuality);
        dest.writeInt(this.mFloorCount);
    }

    public String toString() {
        return "SensorDataInfo{mU4Vercode=" + this.mU4Vercode + ", mU4Step=" + this.mU4Step + ", mU4HeartRate=" + this.mU4HeartRate + ", mSportCalories=" + this.mSportCalories + ", mHeartRate=" + this.mHeartRate + ", mQuality=" + this.mQuality + ", mFloorCount=" + this.mFloorCount + '}';
    }
}
