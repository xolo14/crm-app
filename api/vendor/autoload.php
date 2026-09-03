<?php
spl_autoload_register(function ($class) {
    $prefix = "Firebase\\JWT\\";
    if (strncmp($prefix, $class, strlen($prefix)) !== 0) return;
    $relative = substr($class, strlen($prefix));
    $file = __DIR__ . "/firebase/php-jwt/src/" . str_replace("\\", "/", $relative) . ".php";
    if (is_file($file)) require $file;
});
