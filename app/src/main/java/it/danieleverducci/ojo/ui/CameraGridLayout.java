package it.danieleverducci.ojo.ui;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;

import it.danieleverducci.ojo.utils.DpiUtils;

/**
 * Lays out the camera views.
 * Landscape: a grid that fills the screen (2x2, 2x3, 3x3...).
 * Portrait: one camera under the other, full width, 16:9; inside a ScrollView it scrolls when they don't fit.
 * Fullscreen: one camera fills the view, the others are kept alive as 1x1 pixel views.
 * Cameras are the first children, the remaining children fill the empty cells of the grid.
 * Views are never moved between parents, so rotating or going fullscreen doesn't restart the streams.
 */
public class CameraGridLayout extends ViewGroup {
    private static final float PORTRAIT_CELL_RATIO = 9f / 16f;

    private final int margin;
    private final Rect cell = new Rect();
    private int cameraCount = 0;
    private View fullscreenChild;

    public CameraGridLayout(Context context) {
        this(context, null);
    }

    public CameraGridLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        margin = DpiUtils.DpToPixels(context, 2);
        setChildrenDrawingOrderEnabled(true);
    }

    public void setCameraCount(int cameraCount) {
        this.cameraCount = cameraCount;
        requestLayout();
    }

    /**
     * @param child the camera view to show fullscreen, null to show all cameras
     */
    public void setFullscreenChild(View child) {
        fullscreenChild = child;
        requestLayout();
        invalidate();
    }

    /**
     * True when the cameras are shown one under the other (portrait screen, more than one camera)
     */
    public boolean isPortraitList() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return cameraCount > 1 && dm.heightPixels > dm.widthPixels;
    }

    /**
     * Returns the dimensions of the grid based on the number of elements.
     * Es: to display 3 elements is needed a 4-element grid, with 2 elements per side (a 2x2 grid)
     * Es: to display 6 elements is needed a 9-element grid, with 3 elements per side (a 2x3 grid)
     * Es: to display 7 elements is needed a 9-element grid, with 3 elements per side (a 3x3 grid)
     */
    public static int[] calcGridDimensionsBasedOnNumberOfElements(int elements) {
        int rows = 1;
        int cols = 1;
        while (rows * cols < elements) {
            cols += 1;
            if (rows * cols >= elements) break;
            rows += 1;
        }
        return new int[]{rows, cols};
    }

    private int portraitCellHeight(int width) {
        return Math.round((width - 2 * margin) * PORTRAIT_CELL_RATIO) + 2 * margin;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        boolean list = fullscreenChild == null && isPortraitList();
        // Inside a ScrollView (UNSPECIFIED) ask only for the list height: with fillViewport
        // the ScrollView measures us again with the screen height when that is bigger.
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED)
            height = list ? cameraCount * portraitCellHeight(width) : 0;
        setMeasuredDimension(width, height);

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            calcCell(i, width, height, cell);
            child.measure(
                    MeasureSpec.makeMeasureSpec(cell.width(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(cell.height(), MeasureSpec.EXACTLY)
            );
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            calcCell(i, r - l, b - t, cell);
            getChildAt(i).layout(cell.left, cell.top, cell.right, cell.bottom);
        }
    }

    private void calcCell(int index, int width, int height, Rect out) {
        View child = getChildAt(index);
        if (fullscreenChild != null) {
            if (child == fullscreenChild)
                out.set(0, 0, width, height);
            else if (index < cameraCount)
                out.set(0, 0, 1, 1);   // 1x1 instead of 0x0 because the latter doesn't work on android 13+
            else
                out.setEmpty();
            return;
        }

        if (isPortraitList()) {
            if (index >= cameraCount) {
                out.setEmpty();   // No empty cells in the list
                return;
            }
            int cellHeight = portraitCellHeight(width);
            int top = index * cellHeight;
            out.set(margin, top + margin, width - margin, top + cellHeight - margin);
            return;
        }

        int[] grid = calcGridDimensionsBasedOnNumberOfElements(cameraCount);
        int rows = grid[0];
        int cols = grid[1];
        if (index >= rows * cols) {
            out.setEmpty();
            return;
        }
        int row = index / cols;
        int col = index % cols;
        int left = width * col / cols;
        int top = height * row / rows;
        int right = width * (col + 1) / cols;
        int bottom = height * (row + 1) / rows;
        out.set(left + margin, top + margin, right - margin, bottom - margin);
    }

    @Override
    protected int getChildDrawingOrder(int childCount, int drawingPosition) {
        // The fullscreen camera is drawn last, over the hidden 1x1 views
        int fs = fullscreenChild == null ? -1 : indexOfChild(fullscreenChild);
        if (fs < 0)
            return drawingPosition;
        if (drawingPosition == childCount - 1)
            return fs;
        return drawingPosition >= fs ? drawingPosition + 1 : drawingPosition;
    }
}
