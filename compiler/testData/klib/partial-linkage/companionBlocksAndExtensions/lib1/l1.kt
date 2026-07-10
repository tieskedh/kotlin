class A {
    companion {
        val valChange = "propertyChange.v1"
        val removedVal = 42
        var varChange = valChange
        var removedVar = 42

        fun bodyChange() = "bodyChange.v1"
        fun removedFun() {}
    }

    companion {
        fun blockToObject() = "blockToObject"
    }

    companion object {
        fun objectToBlock() = "objectToBlock"
    }
}

class RemovedBlock {
    companion {
        fun sameFun() = "block"
    }

    companion object {
        fun sameFun() = "object"
    }
}

class NewBlock {
    companion object {
        fun sameFun() = "object"
    }
}

open class BaseClass {}
interface Interface {}
class Derived : BaseClass(), Interface {
   companion {
       fun movedToParentClassFun() = "moved"
       val movedToParentClassProp = 42
       fun movedToParentInterfaceFun() = "moved"
       val movedToParentInterfaceProp = 42
   }
}
