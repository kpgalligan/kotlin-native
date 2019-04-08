/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

// All classes and methods should be used in tests
@file:Suppress("UNUSED")

package conversions

import kotlin.native.concurrent.isFrozen
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

// Generics
abstract class BaseData{
    abstract fun asString():String
}

data class SomeData(val num:Int = 42):BaseData() {
    override fun asString(): String = num.toString()
}

data class SomeOtherData(val str:String):BaseData() {
    fun anotherFun(){}
    override fun asString(): String = str
}

interface NoGeneric<T> {
  fun myVal():T
}

data class SomeGeneric<T>(val t:T):NoGeneric<T>{
  override fun myVal(): T = t
}

class GenOpen<T:Any?>(val arg:T)
class GenNonNull<T:Any>(val arg:T)

class GenCollectionsNull<T>(val arg: T, val coll: List<T>)
class GenCollectionsNonNull<T:Any>(val arg: T, val coll: List<T>)

//Force @class declaration at top of file with Objc variance
object ForceUse {
    val gvo = GenVarOut(SomeData())
}

class GenVarOut<out T:Any>(val arg:T)

class GenVarIn<in T:Any>(tArg:T){
    private val t = tArg

    fun valString():String = t.toString()

    fun goIn(t:T){
        //Just taking a val
    }
}

class GenVarUse<T:Any>(val arg:T){
    fun varUse(a:GenVarUse<out T>, b:GenVarUse<in T>){
        //Should complile but do nothing
    }
}

fun variCoType():GenVarOut<BaseData>{
    val compileVarOutSD:GenVarOut<SomeData> = GenVarOut(SomeData(890))
    val compileVarOut:GenVarOut<BaseData> = compileVarOutSD
    return compileVarOut
}

fun variContraType():GenVarIn<SomeData>{
    val compileVariIn:GenVarIn<BaseData> = GenVarIn(SomeData(1890))
    val compileVariInSD:GenVarIn<SomeData> = compileVariIn
    return compileVariInSD
}

open class GenBase<T:Any>(val t:T)
class GenEx<T:Any, S:Any>(val myT:S, baseT:T):GenBase<T>(baseT)

class GenNullability<T:Any>(val arg: T, val nArg:T?){
    fun asNullable():T? = arg
    val pAsNullable:T?
        get() = arg
}

fun starGeneric(arg: GenNonNull<*>):Any{
    return arg.arg
}

class GenOuter<A:Any>(val a:A){
    class GenNested<B:Any>(val b:B)
    inner class GenInner<C:Any>(val c:C, val aInner:A)
}

class GenOuterSame<A:Any>(val a:A){
    class GenNestedSame<A:Any>(val a:A)
    inner class GenInnerSame<A:Any>(val a:A)
}
