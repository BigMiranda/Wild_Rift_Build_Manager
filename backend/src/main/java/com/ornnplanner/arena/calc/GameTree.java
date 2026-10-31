package com.ornnplanner.arena.calc;

/**
 * The two sides' choices as a matrix of our result against each answer: above 0 we win. Three readings of it: the safest
 * row (maximin), how often each row wins, and a small mixed equilibrium by fictitious play.
 */
public final class GameTree {

    private GameTree() {
    }

    /** What the matrix says. */
    public static class Solution {
        /** The row with the best worst case, and that case. */
        public int maximin;
        public double maximinValue;
        /** For each row: the share of the answers it beats, its worst case and which answer gives it. */
        public double[] winRate;
        public double[] worst;
        public int[] worstAnswer;
        /** Mixed strategies of each side, and the value of the game for us. */
        public double[] mixOurs;
        public double[] mixTheirs;
        public double value;
    }

    public static Solution solve(double[][] m, int rounds) {
        int rows = m.length;
        int cols = rows == 0 ? 0 : m[0].length;
        Solution s = new Solution();
        s.winRate = new double[rows];
        s.worst = new double[rows];
        s.worstAnswer = new int[rows];
        s.maximinValue = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < rows; i++) {
            double worst = Double.POSITIVE_INFINITY;
            int wins = 0;
            for (int j = 0; j < cols; j++) {
                if (m[i][j] > 0) {
                    wins++;
                }
                if (m[i][j] < worst) {
                    worst = m[i][j];
                    s.worstAnswer[i] = j;
                }
            }
            s.worst[i] = worst;
            s.winRate[i] = cols == 0 ? 0 : (double) wins / cols;
            if (worst > s.maximinValue) {
                s.maximinValue = worst;
                s.maximin = i;
            }
        }
        fictitiousPlay(m, s, rounds);
        return s;
    }

    /** Each side answers the other's history with its best reply; the shares of the plays converge to an equilibrium. */
    private static void fictitiousPlay(double[][] m, Solution s, int rounds) {
        int rows = m.length;
        int cols = rows == 0 ? 0 : m[0].length;
        s.mixOurs = new double[rows];
        s.mixTheirs = new double[cols];
        if (rows == 0 || cols == 0) {
            return;
        }
        double[] rowGain = new double[rows];
        double[] colLoss = new double[cols];
        int r = s.maximin;
        for (int k = 0; k < rounds; k++) {
            s.mixOurs[r]++;
            int c = 0;
            for (int j = 0; j < cols; j++) {
                colLoss[j] += m[r][j];
                if (colLoss[j] < colLoss[c]) {
                    c = j;
                }
            }
            s.mixTheirs[c]++;
            int next = 0;
            for (int i = 0; i < rows; i++) {
                rowGain[i] += m[i][c];
                if (rowGain[i] > rowGain[next]) {
                    next = i;
                }
            }
            r = next;
        }
        double value = 0;
        for (int i = 0; i < rows; i++) {
            s.mixOurs[i] /= rounds;
        }
        for (int j = 0; j < cols; j++) {
            s.mixTheirs[j] /= rounds;
        }
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                value += s.mixOurs[i] * s.mixTheirs[j] * m[i][j];
            }
        }
        s.value = value;
    }
}
