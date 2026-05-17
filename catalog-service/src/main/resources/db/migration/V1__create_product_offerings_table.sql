CREATE TABLE product_offerings
(
    id    VARCHAR(50)    NOT NULL,
    name  VARCHAR(255)   NOT NULL,
    price DECIMAL(10, 2) NOT NULL,

    CONSTRAINT pk_product_offerings PRIMARY KEY (id)
);
