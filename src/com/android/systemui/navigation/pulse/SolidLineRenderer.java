/**
 * Copyright (C) 2016 The DirtyUnicorns Project
 * Copyright (C) 2015 The CyanogenMod Project
 *
 * @author: Randall Rushing <randall.rushing@gmail.com>
 *
 * Contributions from The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.android.systemui.navigation.pulse;

import android.animation.ValueAnimator;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuffXfermode;
import android.graphics.PorterDuff.Mode;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.internal.util.NotificationColorUtil;
import com.android.systemui.R;
import com.android.systemui.navigation.utils.ColorAnimator;

public class SolidLineRenderer extends Renderer implements ColorAnimator.ColorAnimationListener {
    private Paint mPaint;
    private Paint mFadePaint;
    private ValueAnimator[] mValueAnimators;
    private FFTAverage[] mFFTAverage;
    private float[] mFFTPoints;
    private int mColor;
    private int mAlbumColor = -1;
    private boolean mAutoColor;
    private int mAccentColor;

    private byte rfk, ifk;
    private int dbValue;
    private float magnitude;
    private float mDbFuzzFactor;
    private boolean mVertical;
    private boolean mLeftInLandscape;
    private int mWidth, mHeight, mUnits;

    private boolean mIsValidStream;
    private boolean mPulseAccentColorEnabled;
    private boolean mLavaLampEnabled;
    private boolean mSmoothingEnabled;
    private CMRendererObserver mObserver;
    private ColorAnimator mLavaLamp;

    private PulseController mController;

    public SolidLineRenderer(Context context, Handler handler, PulseView view,
            PulseController controller) {
        super(context, handler, view);
        mController = controller;
        mColor = Color.TRANSPARENT;
        mLavaLamp = new ColorAnimator();
        mLavaLamp.setColorAnimatorListener(this);
        mPaint = new Paint();
        mPaint.setAntiAlias(true);
        mFadePaint = new Paint();
        mFadePaint.setXfermode(new PorterDuffXfermode(Mode.MULTIPLY));
        mDbFuzzFactor = 5f;
        mObserver = new CMRendererObserver(handler);
        mObserver.updateSettings();
        loadValueAnimators();
    }

    @Override
    public void setLeftInLandscape(boolean leftInLandscape) {
        if (mLeftInLandscape != leftInLandscape) {
            mLeftInLandscape = leftInLandscape;
            onSizeChanged(0, 0, 0, 0);
        }
    }

    private void loadValueAnimators() {
        if (mValueAnimators != null) {
            for (int i = 0; i < mValueAnimators.length; i++) {
                mValueAnimators[i].cancel();
            }
        }
        mValueAnimators = new ValueAnimator[mUnits];
        final boolean isVertical = mVertical;
        for (int i = 0; i < mUnits; i++) {
            final int j;
            if (isVertical) {
                j = i * 4;
            } else {
                j = i * 4 + 1;
            }
            mValueAnimators[i] = new ValueAnimator();
            mValueAnimators[i].setDuration(128);
            mValueAnimators[i].addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    mFFTPoints[j] = (float) animation.getAnimatedValue();
                    postInvalidate();
                }
            });
        }
    }

    private void setPortraitPoints() {
        float units = Float.valueOf(mUnits);
        float barUnit = mWidth / units;
        float barWidth = barUnit * 8f / 9f;
        barUnit = barWidth + (barUnit - barWidth) * units / (units - 1);
        mPaint.setStrokeWidth(barWidth);
        for (int i = 0; i < mUnits; i++) {
            mFFTPoints[i * 4] = mFFTPoints[i * 4 + 2] = i * barUnit + (barWidth / 2);
            mFFTPoints[i * 4 + 1] = mHeight;
            mFFTPoints[i * 4 + 3] = mHeight;
        }
    }

    private void setVerticalPoints() {
        float units = Float.valueOf(mUnits);
        float barUnit = mHeight / units;
        float barHeight = barUnit * 8f / 9f;
        barUnit = barHeight + (barUnit - barHeight) * units / (units - 1);
        mPaint.setStrokeWidth(barHeight);
        for (int i = 0; i < mUnits; i++) {
            mFFTPoints[i * 4 + 1] = mFFTPoints[i * 4 + 3] = i * barUnit + (barHeight / 2);
            mFFTPoints[i * 4] = mLeftInLandscape ? 0 : mWidth;
            mFFTPoints[i * 4 + 2] = mLeftInLandscape ? 0 : mWidth;
        }
    }

    @Override
    public void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (mView.getWidth() > 0 && mView.getHeight() > 0) {
            mWidth = mView.getWidth();
            mHeight = mView.getHeight();
            mVertical = mHeight > mWidth;
            loadValueAnimators();
            if (mVertical) {
                setVerticalPoints();
            } else {
                setPortraitPoints();
            }
        }
    }

    @Override
    public void onStreamAnalyzed(boolean isValid) {
        mIsValidStream = isValid;
        if (isValid) {
            onSizeChanged(0, 0, 0, 0);
            if (mLavaLampEnabled && !mPulseAccentColorEnabled) {
                mLavaLamp.start();
            }
        }
    }

    @Override
    public void onFFTUpdate(byte[] fft) {
        for (int i = 0; i < mUnits; i++) {
            mValueAnimators[i].cancel();
            rfk = fft[i * 2 + 2];
            ifk = fft[i * 2 + 3];
            magnitude = rfk * rfk + ifk * ifk;
            dbValue = magnitude > 0 ? (int) (10 * Math.log10(magnitude)) : 0;
            if (mSmoothingEnabled) {
                dbValue = mFFTAverage[i].average(dbValue);
            }
            if (mVertical) {
                if (mLeftInLandscape) {
                    mValueAnimators[i].setFloatValues(mFFTPoints[i * 4],
                            dbValue * mDbFuzzFactor);
                } else {
                    mValueAnimators[i].setFloatValues(mFFTPoints[i * 4],
                            mFFTPoints[2] - (dbValue * mDbFuzzFactor));
                }
            } else {
                mValueAnimators[i].setFloatValues(mFFTPoints[i * 4 + 1],
                        mFFTPoints[3] - (dbValue * mDbFuzzFactor));
            }
            mValueAnimators[i].start();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawLines(mFFTPoints, mPaint);
        canvas.drawPaint(mFadePaint);
    }

    @Override
    public void destroy() {
        mContext.getContentResolver().unregisterContentObserver(mObserver);
        mLavaLamp.stop();
    }

    @Override
    public void onVisualizerLinkChanged(boolean linked) {
        if (!linked) {
            mLavaLamp.stop();
        }
    }

    @Override
    public void onColorChanged(ColorAnimator colorAnimator, int color) {
        mPaint.setColor(mPulseAccentColorEnabled ? mAccentColor : color);
    }

    @Override
    public void onStartAnimation(ColorAnimator colorAnimator, int firstColor) {
    }

    @Override
    public void onStopAnimation(ColorAnimator colorAnimator, int lastColor) {
        mPaint.setColor(mAutoColor && mAlbumColor != -1 ? mAlbumColor : (mPulseAccentColorEnabled ? mAccentColor : mColor));
    }

    private class CMRendererObserver extends ContentObserver {
        public CMRendererObserver(Handler handler) {
            super(handler);
            register();
        }

        void register() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PULSE_ACCENT_COLOR_ENABLED), false,
                    this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.FLING_PULSE_COLOR), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.FLING_PULSE_LAVALAMP_ENABLED), false,
                    this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PULSE_SOLID_FUDGE_FACTOR), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PULSE_LAVALAMP_SOLID_SPEED), false, this,
                    UserHandle.USER_ALL);
					            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.FLING_PULSE_LAVALAMP_COLOR_FROM), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.FLING_PULSE_LAVALAMP_COLOR_TO), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PULSE_SOLID_UNITS_COUNT), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PULSE_SOLID_UNITS_OPACITY), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PULSE_AUTO_COLOR), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.FLING_PULSE_SMOOTHING_ENABLED), false,
                    this,
                    UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateSettings();
        }

        public void updateSettings() {
            ContentResolver resolver = mContext.getContentResolver();

            mPulseAccentColorEnabled = Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.PULSE_ACCENT_COLOR_ENABLED, 0, UserHandle.USER_CURRENT) == 1;

            mAutoColor = Settings.Secure.getIntForUser(
                    resolver, Settings.Secure.PULSE_AUTO_COLOR, 0,
                    UserHandle.USER_CURRENT) == 1;

            mLavaLampEnabled = !mAutoColor && Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.FLING_PULSE_LAVALAMP_ENABLED, 1, UserHandle.USER_CURRENT) == 1;

            mColor = Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.FLING_PULSE_COLOR,
                    Color.WHITE,
                    UserHandle.USER_CURRENT);

            mAccentColor = mContext.getResources().getColor(R.color.pulseAccentColor);
            if (mPulseAccentColorEnabled) {
                mPaint.setColor(mAccentColor);
            }

            if (!mLavaLampEnabled && !mPulseAccentColorEnabled) {
                int lastColor = mController.getAlbumArtColor();
                mPaint.setColor(mAutoColor && lastColor != -1 ? lastColor : mColor);
            }
            int lavaLampSpeed = Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.PULSE_LAVALAMP_SOLID_SPEED, 10 * 1000,
                    UserHandle.USER_CURRENT);
            mLavaLamp.setAnimationTime(lavaLampSpeed);
            int lavaLampColorFrom= Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.FLING_PULSE_LAVALAMP_COLOR_FROM,
                    0xffff8080,
                    UserHandle.USER_CURRENT);
            int lavaLampColorTo = Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.FLING_PULSE_LAVALAMP_COLOR_TO,
                    0xff8080ff,
                    UserHandle.USER_CURRENT);
            mLavaLamp.setAnimationColors(lavaLampColorFrom, lavaLampColorTo);

            if (mLavaLampEnabled && mIsValidStream && !mPulseAccentColorEnabled) {
                mLavaLamp.start();
            } else {
                mLavaLamp.stop();
            }
            // putFloat, getFloat is better. catch it next time
            mDbFuzzFactor = Settings.Secure.getIntForUser(
                    resolver, Settings.Secure.PULSE_SOLID_FUDGE_FACTOR, 5,
                    UserHandle.USER_CURRENT);
            mSmoothingEnabled = Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.FLING_PULSE_SMOOTHING_ENABLED, 0, UserHandle.USER_CURRENT) == 1;

            int oldUnits = mUnits;
            mUnits = Settings.Secure.getIntForUser(
                    resolver, Settings.Secure.PULSE_SOLID_UNITS_COUNT, 64,
                    UserHandle.USER_CURRENT);
            if (mUnits != oldUnits) {
                mFFTPoints = new float[mUnits * 4];
                if (mSmoothingEnabled) {
                    setupFFTAverage();
                }
                onSizeChanged(0, 0, 0, 0);
            }

            if (mSmoothingEnabled) {
                if (mFFTAverage == null) {
                    setupFFTAverage();
                }
            } else {
                mFFTAverage = null;
            }

            int solidUnitsColor = Settings.Secure.getIntForUser(
                    resolver, Settings.Secure.PULSE_SOLID_UNITS_OPACITY, 200,
                    UserHandle.USER_CURRENT);
            mFadePaint.setColor(Color.argb(solidUnitsColor, 255, 255, 255));
        }

        private void setupFFTAverage() {
            mFFTAverage = new FFTAverage[mUnits];
            for (int i = 0; i < mUnits; i++) {
                mFFTAverage[i] = new FFTAverage();
            }
        }
    }

    public void setColors(boolean colorizedMedia, int[] colors) {
        if (colorizedMedia) {
            // be sure the color will always have an acceptable contrast against black navbar
            mAlbumColor = NotificationColorUtil.findContrastColorAgainstDark(colors[0], 0x000000, true, 2);
            // now be sure the color will always have an acceptable contrast against white navbar
            mAlbumColor = NotificationColorUtil.findContrastColor(mAlbumColor, 0xffffff, true, 2);
        } else {
            mAlbumColor = -1;
        }
        if (mAutoColor && !mLavaLampEnabled) {
            mPaint.setColor(mAlbumColor != -1 ? mAlbumColor : mColor);
            mController.setLastColor(mAlbumColor);
        }
    }
}
