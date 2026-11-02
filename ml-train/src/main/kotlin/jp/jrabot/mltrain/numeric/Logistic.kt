package jp.jrabot.mltrain.numeric

import org.hipparchus.linear.CholeskyDecomposition
import org.hipparchus.linear.MatrixUtils
import org.hipparchus.exception.MathIllegalArgumentException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * L2 正則化ロジスティック回帰（旧 Python の `sklearn.linear_model.LogisticRegression(penalty="l2", solver="lbfgs", fit_intercept=True)` と**同じ目的関数**）:
 *
 *   minimize  f(β, b) = C · Σᵢ wᵢ · (log(1 + exp(zᵢ)) − yᵢ·zᵢ) + ½‖β‖²,   zᵢ = xᵢ·β + b（切片 b は罰則なし）
 *
 * 解き方は **Newton 法（IRLS）+ バックトラッキングの直線探索**。f は強凸（½‖β‖² と、切片方向は wᵢ>0 の 2 階微分）なので最適解は一意で、
 * sklearn の lbfgs と同じ最適解の近傍に収束する。**重みの完全一致は前提にしない**（lbfgs は tol で早めに止まる。許容差は LANGUAGE_MIGRATION §4.2・ML_DESIGN §16）。
 * 線形代数（Cholesky 分解）は Hipparchus を使う（自前の分解は書かない）。乱数は使わない（決定的）。
 *
 * 収束判定: **勾配を C·Σwᵢ で割った値**（= sklearn ≥1.1 が lbfgs に渡す、平均化した目的関数の勾配）の最大絶対値 ≤ [tol]。
 * sklearn の `tol`（lbfgs の gtol）と同じ尺度。[maxIter] 回で収束しなければ [NotConverged]。
 */
object Logistic {
    class NotConverged(
        val iterations: Int,
        val gradientMax: Double,
    ) : RuntimeException("Newton 法が $iterations 回で収束しなかった（|grad|max=$gradientMax）")

    class Fit(
        val weights: DoubleArray,
        val bias: Double,
        val iterations: Int,
        val gradientMax: Double,
        val objective: Double,
    )

    /** log(1 + exp(z)) を桁あふれなく。 */
    fun softplus(z: Double): Double = if (z > 0) z + ln(1.0 + exp(-z)) else ln(1.0 + exp(z))

    fun sigmoid(z: Double): Double = if (z >= 0) 1.0 / (1.0 + exp(-z)) else exp(z).let { it / (1.0 + it) }

    fun objective(
        x: Array<DoubleArray>,
        y: IntArray,
        w: DoubleArray?,
        beta: DoubleArray,
        b: Double,
        c: Double,
    ): Double {
        var data = 0.0
        for (i in x.indices) {
            val z = dot(x[i], beta) + b
            data += (w?.get(i) ?: 1.0) * (softplus(z) - y[i] * z)
        }
        return c * data + 0.5 * dot(beta, beta)
    }

    fun fit(
        x: Array<DoubleArray>,
        y: IntArray,
        w: DoubleArray?,
        c: Double,
        tol: Double,
        maxIter: Int,
    ): Fit {
        val n = x.size
        require(n > 0 && y.size == n && (w == null || w.size == n)) { "入力の長さが合わない" }
        val d = x[0].size
        require(x.all { it.size == d }) { "特徴量の次元が揃っていない" }
        require(y.all { it == 0 || it == 1 }) { "y は 0/1" }
        require(c > 0 && c.isFinite() && tol > 0 && maxIter >= 1)
        val p = d + 1 // 末尾が切片
        val scale = c * (w?.sum() ?: n.toDouble())
        val theta = DoubleArray(p)
        var f = objective(x, y, w, theta.copyOf(d), 0.0, c)
        var gmax = Double.POSITIVE_INFINITY
        for (iter in 0..maxIter) {
            val grad = DoubleArray(p)
            val hess = Array(p) { DoubleArray(p) }
            val beta = theta.copyOf(d)
            val b = theta[d]
            for (i in 0 until n) {
                val xi = x[i]
                val wi = w?.get(i) ?: 1.0
                val s = sigmoid(dot(xi, beta) + b)
                val r = c * wi * (s - y[i])
                val h = c * wi * s * (1.0 - s)
                for (j in 0 until d) {
                    grad[j] += r * xi[j]
                    val hx = h * xi[j]
                    for (k in 0..j) hess[j][k] += hx * xi[k]
                    hess[d][j] += hx
                }
                grad[d] += r
                hess[d][d] += h
            }
            for (j in 0 until d) {
                grad[j] += beta[j]
                hess[j][j] += 1.0
            }
            for (j in 0 until p) for (k in 0 until j) hess[k][j] = hess[j][k]
            gmax = grad.maxOf { abs(it) } / scale
            if (gmax <= tol) return Fit(beta, b, iter, gmax, f)
            if (iter == maxIter) break
            val step = solve(hess, grad)
            // 直線探索（Armijo）。Newton 方向は降下方向（ヘッセ行列が正定値）
            val slope = -dot(grad, step)
            var t = 1.0
            var next: DoubleArray
            var fNext: Double
            while (true) {
                next = DoubleArray(p) { theta[it] - t * step[it] }
                fNext = objective(x, y, w, next.copyOf(d), next[d], c)
                if (fNext <= f - 1e-4 * t * (-slope) || t < 1e-12) break
                t *= 0.5
            }
            if (fNext > f) {
                // 倍精度の限界でこれ以上下がらない。勾配が十分小さければ最適解とみなす（そうでなければ収束失敗）
                if (gmax <= ROUNDOFF_GRADIENT) return Fit(beta, b, iter, gmax, f)
                break
            }
            System.arraycopy(next, 0, theta, 0, p)
            f = fNext
        }
        throw NotConverged(maxIter, gmax)
    }

    private fun solve(
        hess: Array<DoubleArray>,
        grad: DoubleArray,
    ): DoubleArray =
        try {
            CholeskyDecomposition(MatrixUtils.createRealMatrix(hess), 1e-14, 1e-300)
                .solver
                .solve(MatrixUtils.createRealVector(grad))
                .toArray()
        } catch (e: MathIllegalArgumentException) {
            throw ArithmeticException("ヘッセ行列が正定値でない: ${e.message}")
        }

    /** 直線探索が倍精度の丸めで進めなくなったときに、最適解とみなす勾配（C·Σw で割った値）の上限 */
    private const val ROUNDOFF_GRADIENT = 1e-8

    fun dot(
        a: DoubleArray,
        b: DoubleArray,
    ): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** z を ±limit に収めてから sigmoid（推論の Kotlin `LogisticScoring` と同じ算術）。 */
    fun clippedSigmoid(
        z: Double,
        limit: Double,
    ): Double = sigmoid(min(max(z, -limit), limit))
}
