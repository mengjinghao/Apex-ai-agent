package com.apex.agent.android.bullet

class BulletPhysics {
    companion object {
        init {
            System.loadLibrary("streamnative")
        }
    }

    fun createWorld(): Boolean {
        return nativeCreateWorld()
    }

    fun createSphere(mass: Float, x: Float, y: Float, z: Float, radius: Float): Int {
        return nativeCreateSphere(mass, x, y, z, radius)
    }

    fun step(deltaTime: Float) {
        nativeStep(deltaTime)
    }

    fun getPosition(bodyId: Int): FloatArray? {
        return nativeGetPosition(bodyId)
    }

    fun destroyWorld() {
        nativeDestroyWorld()
    }

    private external fun nativeCreateWorld(): Boolean
    private external fun nativeCreateSphere(mass: Float, x: Float, y: Float, z: Float, radius: Float): Int
    private external fun nativeStep(deltaTime: Float)
    private external fun nativeGetPosition(bodyId: Int): FloatArray?
    private external fun nativeDestroyWorld()
}
