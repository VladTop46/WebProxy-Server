# Прокси-сервер на Java

Этот прокси-сервер на Java предназначен для управления сетевым трафиком через прокси.

## Особенности

- Логирование подключений клиентов и других событий в файлы журнала.
- Обработка HTTP-запросов и ответов.
- Поддержка SSL/TLS и HTTPS трафика.

## Требования

- Java Development Kit (JDK)
- Совместимая с Java среда выполнения (JRE)
- Maven (для сборки проекта)

## Установка и запуск

1. Склонируйте репозиторий:

```git clone https://github.com/VladTop46/WebProxy-Server.git```


2. Перейдите в каталог с проектом:

```cd proxy-server```


3. Соберите проект с помощью Maven:

```mvn package```


4. Запустите прокси-сервер:

```java -jar target/WebProxy-1.0-SNAPSHOT.jar```


## Настройка

Вы можете настроить порт прокси и другие параметры в файле `ProxyServer.java`.

