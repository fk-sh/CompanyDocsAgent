package com.agent.retrieval;

import com.agent.core.Chunk;

public class RankedChunk {

    private final Chunk chunk;// 包含原始文本、解析后的文本、向量等信息的原始 Chunk
    private double bm25Score;// BM25 相似度分数
    private double knnScore;// KNN 相似度分数
    private double rrfScore;// RRF 相似度分数
    private double coarseScore;// 粗粒度相似度分数
    private double fineScore;//细粒度相似度分数
    private int coarseRank;// 粗粒度排名
    private int fineRank;//细粒度排名

    public RankedChunk(Chunk chunk) {
        this.chunk = chunk;
    }

    public Chunk chunk() {
        return chunk;
    }

    public double bm25Score() {
        return bm25Score;
    }

    public void setBm25Score(double bm25Score) {
        this.bm25Score = bm25Score;
    }

    public double knnScore() {
        return knnScore;
    }

    public void setKnnScore(double knnScore) {
        this.knnScore = knnScore;
    }

    public double rrfScore() {
        return rrfScore;
    }

    public void setRrfScore(double rrfScore) {
        this.rrfScore = rrfScore;
    }

    public double coarseScore() {
        if (coarseScore == 0.0 && (bm25Score != 0 || knnScore != 0 || rrfScore != 0)) {
            coarseScore = CoarseRanker.computeCoarseScore(this);
        }
        return coarseScore;
    }

    public void setCoarseScore(double coarseScore) {
        this.coarseScore = coarseScore;
    }

    public double fineScore() {
        return fineScore;
    }

    public void setFineScore(double fineScore) {
        this.fineScore = fineScore;
    }

    public int coarseRank() {
        return coarseRank;
    }

    public void setCoarseRank(int coarseRank) {
        this.coarseRank = coarseRank;
    }

    public int fineRank() {
        return fineRank;
    }

    public void setFineRank(int fineRank) {
        this.fineRank = fineRank;
    }
}
